package com.opensab.domain.weather

import com.opensab.domain.settings.TemperatureUnit
import com.opensab.logging.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Single owner of the forecast: the refresh timer, the published [currentWeather] and the cache
 * write all live here, and nothing else may move any of them.
 *
 * Everything platform-shaped is behind a port ([LocationSource], [HttpGetClient], [WeatherCache]),
 * so the whole policy — cadence, debounce, what a failure does — is JVM-testable.
 *
 * Two rules are worth stating because they are not obvious from the code:
 *
 * 1. **A failure never publishes null over a good reading.** "I could not update" and "I know
 *    nothing" are different states, and confusing them blinks the page to standby and closes the
 *    carousel entry mid-ride.
 * 2. **The debounce is on the last attempt, not the last success.** A manual SELECT right behind an
 *    automatic refresh is the same request twice.
 */
class DefaultWeatherSource(
    private val locationSource: LocationSource,
    private val httpClient: HttpGetClient,
    private val cache: WeatherCache,
    private val temperatureUnit: Flow<TemperatureUnit>,
    private val nowMillis: () -> Long,
    /**
     * The rider's cadence for the periodic tick. Read once per session, at [start]: a change with
     * the session live applies to the next one. This class is its only reader.
     */
    private val refreshInterval: suspend () -> Duration,
    private val failureRetryInterval: Duration = 1.minutes,
    private val manualRefreshDebounce: Duration = 60.seconds,
    private val requestTimeout: Duration = 10.seconds
) : WeatherSource {

    private val _currentWeather = MutableStateFlow<WeatherInfo?>(null)
    override val currentWeather: StateFlow<WeatherInfo?> = _currentWeather.asStateFlow()

    private val fetchLock = Mutex()
    private var sessionScope: CoroutineScope? = null
    private var periodicJob: Job? = null
    private var lastAttemptAt: Long? = null
    private var lastWeatherPosition: GeoPosition? = null
    private var lastSuccessfulFetchAt: Long? = null
    private val displacementThresholdKm = 5.0
    private val maxStationaryIntervalMs = 45.minutes.inWholeMilliseconds

    override fun start(scope: CoroutineScope) {
        if (sessionScope != null) {
            Log.d("Weather source already started")
            return
        }
        sessionScope = scope

        periodicJob = scope.launch {
            val tick = refreshInterval()
            Log.d("Weather refresh interval for this session: %d min", tick.inWholeMinutes)

            // The stored reading opens the carousel entry on the first frame, before the network
            // has had a chance to answer.
            cache.load()?.let {
                _currentWeather.value = it
                Log.d("Restored the last weather reading from cache")
            }

            // `drop(1)` is what makes the first emission not count as a change: it is the current
            // preference, not the rider moving the switch.
            launch {
                temperatureUnit.distinctUntilChanged().drop(1).collect {
                    Log.d("Temperature unit changed to %s, refreshing weather", it)
                    fetch(force = true, bypassDebounce = true)
                }
            }

            // Immediate tick, then periodic. A failed attempt comes back after a minute instead: a
            // session that starts without a fix must not leave the page blank for a quarter of an
            // hour.
            while (isActive) {
                val settled = fetch(force = false)
                delay(if (settled) tick else failureRetryInterval)
            }
        }
    }

    override fun stop() {
        periodicJob?.cancel()
        periodicJob = null
        sessionScope = null
        Log.d("Weather source stopped")
    }

    override fun refresh() {
        val scope = sessionScope
        if (scope == null) {
            Log.d("Weather refresh requested before the session started, ignoring it")
            return
        }
        scope.launch { fetch(force = true) }
    }

    /**
     * Returns false when the attempt failed — no fix, no answer, or an unusable one — which is what
     * brings the periodic loop back early. A skipped attempt (debounced, or not far enough) is
     * settled: nothing went wrong.
     */
    private suspend fun fetch(force: Boolean, bypassDebounce: Boolean = false): Boolean = fetchLock.withLock {
        val now = nowMillis()
        val since = lastAttemptAt?.let { now - it }
        if (!bypassDebounce && since != null && since < manualRefreshDebounce.inWholeMilliseconds) {
            Log.d("Weather refresh debounced: last attempt was %d ms ago", since)
            return@withLock true
        }

        // Without a fix, the place of the last good reading stands in, and the attempt still
        // reads as failed so the loop keeps looking for a real fix every minute.
        val fix = locationSource.currentPosition()
        val position = fix ?: (lastWeatherPosition ?: cache.lastPosition())?.also {
            Log.d("No location fix available, using the position of the last weather reading")
        }
        if (position == null) {
            Log.d("No location fix available, keeping the last weather reading")
            return@withLock false
        }

        if (!force) {
            val lastPos = lastWeatherPosition
            val lastFetch = lastSuccessfulFetchAt
            if (lastPos != null && lastFetch != null) {
                val distance = haversineDistanceKm(
                    lastPos.latitude,
                    lastPos.longitude,
                    position.latitude,
                    position.longitude
                )
                val elapsed = now - lastFetch
                if (distance < displacementThresholdKm && elapsed < maxStationaryIntervalMs) {
                    Log.d(
                        "Displacement %f km < threshold and elapsed %d ms < max interval, skipping fetch",
                        distance,
                        elapsed
                    )
                    return@withLock fix != null
                }
            }
        }

        lastAttemptAt = now

        val unit = temperatureUnit.first()
        val url = OpenMeteoUrlBuilder.currentWeather(position.latitude, position.longitude, unit)
        val body = try {
            withTimeout(requestTimeout) { httpClient.get(url) }
        } catch (e: Exception) {
            Log.w(e, "Weather request failed, keeping the last reading")
            return@withLock false
        }

        val observation = OpenMeteoResponseParser.parse(body)
        if (observation == null) {
            Log.w("Weather response was not usable, keeping the last reading")
            return@withLock false
        }

        lastWeatherPosition = position
        lastSuccessfulFetchAt = now

        // The hours ahead ride along in the same response, and a body that carries none is still a
        // reading: the carousel entry hangs on the reading, not on the forecast.
        val segments = selectForecastSegments(OpenMeteoResponseParser.parseHourly(body), now)

        val info = WeatherInfo(
            locationName = position.locality ?: "",
            temperature = observation.temperature,
            temperatureUnit = observation.unitSymbol,
            caption = observation.condition.caption,
            type = observation.condition.type,
            hourly = segments,
            timestamp = now,
            latitude = position.latitude,
            longitude = position.longitude
        )
        _currentWeather.value = info
        cache.save(info)
        Log.d("Weather updated: %d%s %s", info.temperature, info.temperatureUnit, info.caption)
        fix != null
    }
}
