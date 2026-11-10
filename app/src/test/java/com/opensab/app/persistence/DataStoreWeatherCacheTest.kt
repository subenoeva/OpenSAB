package com.opensab.app.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opensab.domain.weather.GeoPosition
import com.opensab.domain.weather.WeatherConditionType
import com.opensab.domain.weather.WeatherForecastSegment
import com.opensab.domain.weather.WeatherInfo
import com.opensab.domain.weather.WmoCaption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * In-memory DataStore throughout: on Windows a second write against a real file dies with
 * `IOException: Unable to rename`, because `File.renameTo` refuses an existing destination.
 */
class DataStoreWeatherCacheTest {

    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val writeLock = Mutex()

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences = writeLock.withLock {
            transform(state.value).also { state.value = it }
        }
    }

    private val reading = WeatherInfo(
        locationName = "Barcelona",
        temperature = 24,
        temperatureUnit = "°C",
        caption = WmoCaption.CLEAR,
        type = WeatherConditionType.SUNNY,
        timestamp = 1_000_000L
    )

    @Test
    fun roundTripsAReadingThroughTheStore() = runBlocking {
        val store = FakePreferencesDataStore()
        val cache = DataStoreWeatherCache(store, nowMillis = { 1_000_000L })

        cache.save(reading)

        assertEquals(reading, cache.load())
    }

    /**
     * The reading is too old to paint but its place is still where the rider was: the stand-in
     * position for a session that starts with no fix.
     */
    @Test
    fun lastPositionOutlivesTheTtl() = runBlocking {
        val store = FakePreferencesDataStore()
        val stale = 1_000_000L + 48.hours.inWholeMilliseconds
        DataStoreWeatherCache(store, nowMillis = { 1_000_000L })
            .save(reading.copy(latitude = 41.39, longitude = 2.17))

        val cache = DataStoreWeatherCache(store, nowMillis = { stale })

        assertNull(cache.load())
        assertEquals(GeoPosition(latitude = 41.39, longitude = 2.17, locality = "Barcelona"), cache.lastPosition())
    }

    @Test
    fun lastPositionIsNullForAReadingStoredWithoutCoordinates() = runBlocking {
        val store = FakePreferencesDataStore()
        val cache = DataStoreWeatherCache(store, nowMillis = { 1_000_000L })
        cache.save(reading)

        assertNull(cache.lastPosition())
    }

    @Test
    fun lastPositionIsNullWhenNothingWasEverStored() = runBlocking {
        assertNull(DataStoreWeatherCache(FakePreferencesDataStore(), nowMillis = { 1_000_000L }).lastPosition())
    }

    @Test
    fun returnsNullWhenNothingWasEverStored() = runBlocking {
        val cache = DataStoreWeatherCache(FakePreferencesDataStore(), nowMillis = { 1_000_000L })

        assertNull(cache.load())
    }

    /**
     * Better a closed carousel entry than the day before yesterday's temperature on the cluster.
     */
    @Test
    fun dropsAReadingOlderThanTheTtl() = runBlocking {
        val store = FakePreferencesDataStore()
        val stale = 1_000_000L + 2.hours.inWholeMilliseconds + 1.milliseconds.inWholeMilliseconds
        val cache = DataStoreWeatherCache(store, nowMillis = { stale })

        DataStoreWeatherCache(store, nowMillis = { 1_000_000L }).save(reading)

        assertNull(cache.load())
    }

    @Test
    fun keepsAReadingInsideTheTtl() = runBlocking {
        val store = FakePreferencesDataStore()
        val fresh = 1_000_000L + 2.hours.inWholeMilliseconds
        val cache = DataStoreWeatherCache(store, nowMillis = { fresh })

        DataStoreWeatherCache(store, nowMillis = { 1_000_000L }).save(reading)

        assertEquals(reading, cache.load())
    }

    /**
     * The hours ahead are what the page sings, so losing them in the store would leave a page that
     * only exists to speak with nothing to say after a restart.
     */
    @Test
    fun segmentsSurviveARoundTripThroughTheCache() = runBlocking {
        val store = FakePreferencesDataStore()
        val cache = DataStoreWeatherCache(store, nowMillis = { 1_000_000L })
        val withForecast = reading.copy(
            hourly = listOf(
                WeatherForecastSegment(
                    timestamp = 1_010_800_000L,
                    temperature = 21,
                    caption = WmoCaption.PARTLY_CLOUDY,
                    type = WeatherConditionType.SUN_AND_CLOUD
                )
            )
        )

        cache.save(withForecast)

        assertEquals(withForecast, cache.load())
    }

    /**
     * The JSON a phone of the test fleet already has on disk, written by an older build: no
     * `hourly` and a `forecastSummary` that no longer exists. It loads, and it loads as a reading —
     * anything else would close the carousel entry on every phone that had a cache.
     *
     * What it does not carry is a `caption`, which is newer than this entry: the English sentence
     * comes back in the compatibility field and the caption comes back UNKNOWN, which is one HTTP
     * round trip's worth of wrong and nothing on disk's.
     */
    @Test
    fun aReadingStoredBeforeForecastSegmentsExistedStillLoads() = runBlocking {
        val store = FakePreferencesDataStore()
        store.edit {
            it[stringPreferencesKey("weather_last_reading")] = """
                {"locationName":"Barcelona","temperature":24,"temperatureUnit":"°C",
                 "condition":"Clear","type":"SUNNY","forecastSummary":"","timestamp":1000000}
            """.trimIndent()
        }

        val restored = DataStoreWeatherCache(store, nowMillis = { 1_000_000L }).load()

        assertEquals(
            reading.copy(condition = "Clear", caption = WmoCaption.UNKNOWN),
            restored
        )
    }

    @Test
    fun returnsNullInsteadOfThrowingOnCorruptStoredJson() = runBlocking {
        val store = FakePreferencesDataStore()
        store.edit { it[stringPreferencesKey("weather_last_reading")] = "{not json" }

        val cache = DataStoreWeatherCache(store, nowMillis = { 1_000_000L })

        assertNull(cache.load())
    }

    /**
     * A clock that runs backwards — a phone picking up network time mid-ride — must not make a
     * reading look stale, or the entry would close for no reason.
     */
    @Test
    fun keepsAReadingWhoseTimestampIsInTheFuture() = runBlocking {
        val store = FakePreferencesDataStore()
        val cache = DataStoreWeatherCache(store, nowMillis = { 1_000_000L - 5_000L })

        DataStoreWeatherCache(store, nowMillis = { 1_000_000L }).save(reading)

        assertEquals(reading, cache.load())
    }
}
