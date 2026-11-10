package com.opensab.domain.weather

import com.opensab.domain.settings.TemperatureUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultWeatherSourceTest {

    private class FakeLocationSource(var position: GeoPosition?) : LocationSource {
        var calls = 0
        override suspend fun currentPosition(): GeoPosition? {
            calls++
            return position
        }
    }

    private class FakeHttpGetClient : HttpGetClient {
        val urls = mutableListOf<String>()
        var respond: (String) -> String = { bodyFor(temperature = "21.6", code = "2") }

        override suspend fun get(url: String): String {
            urls += url
            return respond(url)
        }
    }

    private class FakeWeatherCache : WeatherCache {
        var stored: WeatherInfo? = null
        var loaded: WeatherInfo? = null
        var storedPosition: GeoPosition? = null
        var saves = 0

        override suspend fun load(): WeatherInfo? = loaded

        override suspend fun lastPosition(): GeoPosition? = storedPosition

        override suspend fun save(info: WeatherInfo) {
            stored = info
            saves++
        }
    }

    private val madrid = GeoPosition(latitude = 40.42, longitude = -3.70, locality = "Madrid")

    private fun source(
        location: FakeLocationSource,
        http: FakeHttpGetClient,
        cache: FakeWeatherCache,
        unit: MutableStateFlow<TemperatureUnit> = MutableStateFlow(TemperatureUnit.CELSIUS),
        refreshInterval: suspend () -> Duration = { 15.minutes },
        now: () -> Long
    ) = DefaultWeatherSource(
        locationSource = location,
        httpClient = http,
        cache = cache,
        temperatureUnit = unit,
        nowMillis = now,
        refreshInterval = refreshInterval
    )

    @Test
    fun publishesTheCachedReadingBeforeTheFirstFetch() = runTest {
        val cache = FakeWeatherCache().apply {
            loaded = WeatherInfo(
                locationName = "Barcelona",
                temperature = 24,
                caption = WmoCaption.CLEAR,
                type = WeatherConditionType.SUNNY,
                timestamp = 1L
            )
        }
        val http = FakeHttpGetClient()
        // No fix, so nothing can overwrite the cached reading: this test is about the cache alone.
        val weather = source(FakeLocationSource(position = null), http, cache) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()

        assertEquals("Barcelona", weather.currentWeather.value?.locationName)
        assertTrue(http.urls.isEmpty())
    }

    @Test
    fun publishesNothingWhenTheCacheIsEmptyAndThereIsNoFix() = runTest {
        val weather = source(
            FakeLocationSource(position = null),
            FakeHttpGetClient(),
            FakeWeatherCache()
        ) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()

        assertNull(weather.currentWeather.value)
    }

    /**
     * An immediate tick, then every fifteen minutes: the source only lives while a session does,
     * and an hourly period would be one reading per ride.
     */
    @Test
    fun fetchesImmediatelyOnStartAndThenEveryFifteenMinutes() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(madrid)
        val weather = source(location, http, FakeWeatherCache()) {
            testScheduler.currentTime
        }

        weather.start(backgroundScope)
        runCurrent()
        assertEquals(1, http.urls.size)

        location.position = madrid.copy(latitude = 40.48)
        advanceTimeBy(15.minutes)
        runCurrent()
        assertEquals(2, http.urls.size)

        location.position = madrid.copy(latitude = 40.54)
        advanceTimeBy(15.minutes)
        runCurrent()
        assertEquals(3, http.urls.size)

        location.position = madrid.copy(latitude = 40.60)
        advanceTimeBy(15.minutes)
        runCurrent()
        assertEquals(4, http.urls.size)
    }

    @Test
    fun ticksAtTheIntervalTheRiderChose() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(madrid)
        val weather = source(location, http, FakeWeatherCache(), refreshInterval = { 30.minutes }) {
            testScheduler.currentTime
        }

        weather.start(backgroundScope)
        runCurrent()
        assertEquals(1, http.urls.size)

        location.position = madrid.copy(latitude = 40.48)
        advanceTimeBy(29.minutes)
        runCurrent()
        assertEquals("No tick before the chosen interval", 1, http.urls.size)

        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(2, http.urls.size)
    }

    /** Read once per session, at start: a change with the session live applies to the next one. */
    @Test
    fun readsTheRefreshIntervalOncePerSession() = runTest {
        var reads = 0
        var chosen = 15.minutes
        val location = FakeLocationSource(madrid)
        val http = FakeHttpGetClient()
        val weather = source(location, http, FakeWeatherCache(), refreshInterval = { reads++; chosen }) {
            testScheduler.currentTime
        }

        val first = CoroutineScope(backgroundScope.coroutineContext + Job())
        weather.start(first)
        runCurrent()
        chosen = 5.minutes
        location.position = madrid.copy(latitude = 40.48)
        advanceTimeBy(14.minutes)
        runCurrent()
        assertEquals("The live session keeps the interval it started with", 1, http.urls.size)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(2, http.urls.size)
        assertEquals(1, reads)

        first.cancel()
        weather.stop()
        val second = CoroutineScope(backgroundScope.coroutineContext + Job())
        weather.start(second)
        runCurrent()
        assertEquals("The next session reads it again", 2, reads)
        second.cancel()
    }

    /**
     * A session that starts with no fix must not wait the full fifteen minutes before asking again
     * and leave the weather slot blank: a failed attempt retries a minute later.
     */
    @Test
    fun retriesAMinuteAfterStartingWithoutAFix() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(position = null)
        val weather = source(location, http, FakeWeatherCache()) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()
        assertTrue(http.urls.isEmpty())

        location.position = madrid
        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(1, http.urls.size)
        assertEquals("Madrid", weather.currentWeather.value?.locationName)
    }

    @Test
    fun retriesAMinuteAfterAFailedRequest() = runTest {
        val http = FakeHttpGetClient().apply { respond = { throw java.io.IOException("no route to host") } }
        val weather = source(FakeLocationSource(madrid), http, FakeWeatherCache()) {
            testScheduler.currentTime
        }

        weather.start(backgroundScope)
        runCurrent()
        assertEquals(1, http.urls.size)

        http.respond = { bodyFor(temperature = "21.6", code = "2") }
        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(2, http.urls.size)
        assertEquals(22, weather.currentWeather.value?.temperature)
    }

    /** After a good reading the cadence is the normal one: no retry a minute later. */
    @Test
    fun doesNotRetryEarlyAfterAGoodReading() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(madrid)
        val weather = source(location, http, FakeWeatherCache()) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()
        location.position = madrid.copy(latitude = 40.60)
        advanceTimeBy(14.minutes)
        runCurrent()

        assertEquals(1, http.urls.size)
    }

    /**
     * With no fix the stored position of the last good reading is still a place: the rider usually
     * sets off near where the last ride ended, and a reading for there beats a blank page. It is
     * used even when the reading itself is past the cache's TTL.
     */
    @Test
    fun fetchesForTheStoredPositionWhenThereIsNoFix() = runTest {
        val http = FakeHttpGetClient()
        val cache = FakeWeatherCache().apply { storedPosition = madrid }
        val weather = source(FakeLocationSource(position = null), http, cache) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()

        assertEquals(1, http.urls.size)
        assertTrue(http.urls.single().contains("latitude=40.42"))
        assertEquals("Madrid", weather.currentWeather.value?.locationName)
    }

    /** The stored position is a stand-in: the source keeps looking for a real fix every minute. */
    @Test
    fun keepsLookingForARealFixAfterUsingTheStoredPosition() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(position = null)
        val cache = FakeWeatherCache().apply { storedPosition = madrid }
        val weather = source(location, http, cache) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()
        assertEquals(1, http.urls.size)

        location.position = GeoPosition(latitude = 42.24, longitude = -8.72, locality = "Vigo")
        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(2, http.urls.size)
        assertEquals("Vigo", weather.currentWeather.value?.locationName)
    }

    /** And while there is still no fix, the minute retries do not hit the network again. */
    @Test
    fun doesNotRefetchTheStoredPositionEveryMinute() = runTest {
        val http = FakeHttpGetClient()
        val cache = FakeWeatherCache().apply { storedPosition = madrid }
        val weather = source(FakeLocationSource(position = null), http, cache) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()
        advanceTimeBy(10.minutes)
        runCurrent()

        assertEquals(1, http.urls.size)
    }

    @Test
    fun savesThePositionAlongsideTheReading() = runTest {
        val cache = FakeWeatherCache()
        val weather = source(FakeLocationSource(madrid), FakeHttpGetClient(), cache) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()

        assertEquals(40.42, cache.stored?.latitude)
        assertEquals(-3.70, cache.stored?.longitude)
    }

    @Test
    fun periodicTickSkipsHttpFetchWhenDisplacementUnderFiveKmAndElapsedUnderFortyFiveMinutes() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(madrid)
        val weather = source(location, http, FakeWeatherCache()) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()
        assertEquals(1, http.urls.size)

        // Small displacement (< 5 km) and elapsed 15 min (< 45 min) -> skips HTTP fetch
        location.position = madrid.copy(latitude = 40.43)
        advanceTimeBy(15.minutes)
        runCurrent()
        assertEquals(1, http.urls.size)

        // Elapsed 30 minutes, still under 5 km -> skips HTTP fetch
        advanceTimeBy(15.minutes)
        runCurrent()
        assertEquals(1, http.urls.size)
    }

    @Test
    fun periodicTickQueriesHttpFetchWhenDisplacementReachesFiveKilometers() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(madrid)
        val weather = source(location, http, FakeWeatherCache()) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()
        assertEquals(1, http.urls.size)

        // Move >= 5 km (from 40.42 to 40.48 is ~6.6 km) -> queries HTTP fetch
        location.position = madrid.copy(latitude = 40.48)
        advanceTimeBy(15.minutes)
        runCurrent()
        assertEquals(2, http.urls.size)
    }

    @Test
    fun periodicTickQueriesHttpFetchWhenStationaryAfterFortyFiveMinutes() = runTest {
        val http = FakeHttpGetClient()
        val weather = source(FakeLocationSource(madrid), http, FakeWeatherCache()) {
            testScheduler.currentTime
        }

        weather.start(backgroundScope)
        runCurrent()
        assertEquals(1, http.urls.size)

        advanceTimeBy(15.minutes)
        runCurrent()
        assertEquals(1, http.urls.size)

        advanceTimeBy(15.minutes)
        runCurrent()
        assertEquals(1, http.urls.size)

        // At 45 minutes stationary, elapsed reaches max interval -> queries HTTP
        advanceTimeBy(15.minutes)
        runCurrent()
        assertEquals(2, http.urls.size)
    }

    @Test
    fun manualRefreshBypassesDisplacementAndTimeThresholds() = runTest {
        val http = FakeHttpGetClient()
        val weather = source(FakeLocationSource(madrid), http, FakeWeatherCache()) {
            testScheduler.currentTime
        }

        weather.start(backgroundScope)
        runCurrent()
        assertEquals(1, http.urls.size)

        // Stationary (< 5 km) and elapsed 61 seconds (< 45 min): manual refresh forces fetch
        advanceTimeBy(61.seconds)
        weather.refresh()
        runCurrent()
        assertEquals(2, http.urls.size)
    }

    @Test
    fun publishesAFetchedReadingWithTheLocalityOfTheFix() = runTest {
        val http = FakeHttpGetClient()
        val weather = source(FakeLocationSource(madrid), http, FakeWeatherCache()) {
            testScheduler.currentTime
        }

        weather.start(backgroundScope)
        runCurrent()

        assertEquals(
            WeatherInfo(
                locationName = "Madrid",
                temperature = 22,
                temperatureUnit = "°C",
                caption = WmoCaption.PARTLY_CLOUDY,
                type = WeatherConditionType.SUN_AND_CLOUD,
                timestamp = 0L,
                latitude = 40.42,
                longitude = -3.70
            ),
            weather.currentWeather.value
        )
    }

    /**
     * The page that only speaks needs the hours ahead, because the reading itself is exactly what
     * the carousel already shows without entering.
     */
    @Test
    fun aFetchPublishesTheHourlySegmentsAlongsideTheCurrentReading() = runTest {
        val http = FakeHttpGetClient().apply { respond = { bodyWithHourly() } }
        val cache = FakeWeatherCache()
        val weather = source(FakeLocationSource(madrid), http, cache) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()

        val published = weather.currentWeather.value
        assertEquals(22, published?.temperature)
        assertEquals(
            listOf(3 * 3_600_000L, 6 * 3_600_000L, 9 * 3_600_000L),
            published?.hourly?.map { it.timestamp }
        )
        assertEquals(WmoCaption.RAIN, published?.hourly?.get(1)?.caption)
        assertEquals(WeatherConditionType.RAIN, published?.hourly?.get(1)?.type)
        // What is published is what is stored: a restart must not lose the hours ahead.
        assertEquals(published, cache.stored)
    }

    /**
     * A response with no `hourly` block, which is also what a slow or partial answer looks like. A
     * reading with no hours ahead is still a reading, and the carousel entry stays open on it.
     */
    @Test
    fun aResponseWithNoHourlyBlockStillPublishesTheCurrentReading() = runTest {
        val weather = source(FakeLocationSource(madrid), FakeHttpGetClient(), FakeWeatherCache()) {
            testScheduler.currentTime
        }

        weather.start(backgroundScope)
        runCurrent()

        assertEquals(22, weather.currentWeather.value?.temperature)
        assertTrue(weather.currentWeather.value!!.hourly.isEmpty())
    }

    @Test
    fun leavesTheLocationNameEmptyWhenReverseGeocodingFoundNothing() = runTest {
        val weather = source(
            FakeLocationSource(madrid.copy(locality = null)),
            FakeHttpGetClient(),
            FakeWeatherCache()
        ) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()

        assertEquals("", weather.currentWeather.value?.locationName)
    }

    @Test
    fun manualRefreshForcesAFetch() = runTest {
        val http = FakeHttpGetClient()
        val weather = source(FakeLocationSource(madrid), http, FakeWeatherCache()) {
            testScheduler.currentTime
        }

        weather.start(backgroundScope)
        runCurrent()
        advanceTimeBy(61.seconds)

        weather.refresh()
        runCurrent()

        assertEquals(2, http.urls.size)
    }

    /**
     * SELECT comes from a handlebar switch that bounces, and the sky does not move in a minute.
     */
    @Test
    fun manualRefreshInsideTheDebounceWindowDoesNotTouchTheNetwork() = runTest {
        val http = FakeHttpGetClient()
        val weather = source(FakeLocationSource(madrid), http, FakeWeatherCache()) {
            testScheduler.currentTime
        }

        weather.start(backgroundScope)
        runCurrent()
        advanceTimeBy(30.seconds)

        weather.refresh()
        weather.refresh()
        runCurrent()

        assertEquals(1, http.urls.size)
    }

    @Test
    fun refreshBeforeStartIsANoOp() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(madrid)
        val weather = source(location, http, FakeWeatherCache()) { testScheduler.currentTime }

        weather.refresh()
        runCurrent()

        assertEquals(0, location.calls)
        assertTrue(http.urls.isEmpty())
    }

    /**
     * "I could not update" and "I know nothing" are different states: publishing null over a good
     * reading would blink the page to standby and close the carousel entry mid-ride.
     */
    @Test
    fun keepsTheLastGoodReadingWhenTheNetworkFails() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(madrid)
        val weather = source(location, http, FakeWeatherCache()) {
            testScheduler.currentTime
        }

        weather.start(backgroundScope)
        runCurrent()
        val good = weather.currentWeather.value

        http.respond = { throw java.io.IOException("no route to host") }
        location.position = madrid.copy(latitude = 40.48)
        advanceTimeBy(15.minutes)
        runCurrent()

        assertEquals(good, weather.currentWeather.value)
    }

    @Test
    fun keepsTheLastGoodReadingWhenTheResponseIsUnparseable() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(madrid)
        val weather = source(location, http, FakeWeatherCache()) {
            testScheduler.currentTime
        }

        weather.start(backgroundScope)
        runCurrent()
        val good = weather.currentWeather.value

        http.respond = { "<html>502 Bad Gateway</html>" }
        location.position = madrid.copy(latitude = 40.48)
        advanceTimeBy(15.minutes)
        runCurrent()

        assertEquals(good, weather.currentWeather.value)
    }

    @Test
    fun keepsTheLastGoodReadingWhenTheFixDisappears() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(madrid)
        val weather = source(location, http, FakeWeatherCache()) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()
        val good = weather.currentWeather.value

        location.position = null
        advanceTimeBy(15.minutes)
        runCurrent()

        assertEquals(good, weather.currentWeather.value)
        assertEquals(1, http.urls.size)
    }

    @Test
    fun writesTheCacheOnlyAfterAGoodFetch() = runTest {
        val cache = FakeWeatherCache()
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(madrid)
        val weather = source(location, http, cache) { testScheduler.currentTime }

        weather.start(backgroundScope)
        runCurrent()
        assertEquals(1, cache.saves)
        assertEquals(weather.currentWeather.value, cache.stored)

        http.respond = { throw java.io.IOException("no route to host") }
        location.position = madrid.copy(latitude = 40.48)
        advanceTimeBy(15.minutes)
        runCurrent()

        assertEquals(1, cache.saves)
    }

    @Test
    fun aUnitChangeForcesAFetchButTheFirstEmissionDoesNot() = runTest {
        val http = FakeHttpGetClient()
        val unit = MutableStateFlow(TemperatureUnit.CELSIUS)
        val weather = source(FakeLocationSource(madrid), http, FakeWeatherCache(), unit) {
            testScheduler.currentTime
        }

        weather.start(backgroundScope)
        runCurrent()
        assertEquals(1, http.urls.size)
        assertTrue(http.urls.single().endsWith("temperature_unit=celsius"))

        unit.value = TemperatureUnit.FAHRENHEIT
        runCurrent()

        assertEquals(2, http.urls.size)
        assertTrue(http.urls.last().endsWith("temperature_unit=fahrenheit"))
    }

    /**
     * A request that never answers must not wedge the periodic loop the way a missing Receipt
     * wedged the ControlPoint queue.
     */
    @Test
    fun givesUpOnARequestThatNeverAnswers() = runTest {
        val http = object : HttpGetClient {
            var calls = 0
            override suspend fun get(url: String): String {
                calls++
                kotlinx.coroutines.delay(1.minutes)
                return bodyFor("21.6", "2")
            }
        }
        val weather = DefaultWeatherSource(
            locationSource = FakeLocationSource(madrid),
            httpClient = http,
            cache = FakeWeatherCache(),
            temperatureUnit = MutableStateFlow(TemperatureUnit.CELSIUS),
            nowMillis = { testScheduler.currentTime },
            refreshInterval = { 15.minutes }
        )

        weather.start(backgroundScope)
        runCurrent()
        assertNull(weather.currentWeather.value)

        // The request gives up ten seconds in, and a failed attempt retries a minute after that.
        advanceTimeBy(70.seconds - 1.seconds)
        runCurrent()
        assertEquals(1, http.calls)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, http.calls)
    }

    @Test
    fun survivesSecondSessionAfterStop() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(madrid)
        val weather = source(location, http, FakeWeatherCache()) { testScheduler.currentTime }

        val scope1 = CoroutineScope(backgroundScope.coroutineContext + Job())
        weather.start(scope1)
        runCurrent()
        assertEquals(1, http.urls.size)

        // Session 1 dies and source is stopped
        scope1.cancel()
        weather.stop()

        // Session 2 starts on a new scope
        val scope2 = CoroutineScope(backgroundScope.coroutineContext + Job())
        weather.start(scope2)
        runCurrent()

        // Advance time past debounce (60s) and trigger a refresh on session 2
        advanceTimeBy(61.seconds)
        weather.refresh()
        runCurrent()

        assertEquals(
            "session 2 must be able to refresh on its active scope",
            2,
            http.urls.size
        )
        scope2.cancel()
    }

    @Test
    fun stopCancelsPeriodicLoopOnOriginalScope() = runTest {
        val http = FakeHttpGetClient()
        val location = FakeLocationSource(madrid)
        val weather = source(location, http, FakeWeatherCache()) { testScheduler.currentTime }

        val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
        weather.start(scope)
        runCurrent()
        assertEquals(1, http.urls.size)

        // Stop session, but the original scope remains alive (e.g. application scope from scanner)
        weather.stop()

        // Location changes significantly (>5km) and 16 minutes pass
        location.position = GeoPosition(latitude = 41.0, longitude = -3.70, locality = "Segovia")
        advanceTimeBy(16.minutes)
        runCurrent()

        assertEquals(
            "stopping the weather source must cancel the periodic loop",
            1,
            http.urls.size
        )
        scope.cancel()
    }

}

private fun bodyFor(temperature: String, code: String): String = """
    {
      "current_units": { "temperature_2m": "°C" },
      "current": { "temperature_2m": $temperature, "weather_code": $code }
    }
""".trimIndent()

/**
 * The shape `forecast_days=2&timeformat=unixtime` answers: a slot per hour from midnight, as three
 * arrays walked index by index. The clock of `runTest` starts at zero, so slot `n` is `n` hours in.
 */
private fun bodyWithHourly(): String {
    val times = (0 until 24).joinToString(", ") { (it * 3600).toString() }
    val temperatures = (0 until 24).joinToString(", ") { (10 + it).toString() + ".0" }
    // Every slot rains except the very first, so a picked slot cannot be confused with slot zero.
    val codes = (0 until 24).joinToString(", ") { if (it == 0) "0" else "61" }
    return """
        {
          "current_units": { "temperature_2m": "°C" },
          "current": { "temperature_2m": 21.6, "weather_code": 2 },
          "hourly": {
            "time": [$times],
            "temperature_2m": [$temperatures],
            "weather_code": [$codes]
          }
        }
    """.trimIndent()
}
