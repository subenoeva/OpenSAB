package com.opensab.app.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opensab.domain.weather.GeoPosition
import com.opensab.domain.weather.WeatherCache
import com.opensab.domain.weather.WeatherInfo
import com.opensab.logging.Log
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** [WeatherCache] backed by the shared DataStore Preferences file, as JSON under one key. */
class DataStoreWeatherCache(
    private val dataStore: DataStore<Preferences>,
    private val nowMillis: () -> Long,
    private val ttl: Duration = 2.hours
) : WeatherCache {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): WeatherInfo? {
        val stored = stored() ?: return null

        // A phone that picks up network time mid-ride can move the clock backwards; an age below
        // zero is a clock jump, not a fresh reading, and it must not close the carousel entry.
        val age = nowMillis() - stored.timestamp
        if (age > ttl.inWholeMilliseconds) {
            Log.d("Stored weather reading is %d ms old, past the %s TTL", age, ttl)
            return null
        }
        return stored
    }

    override suspend fun lastPosition(): GeoPosition? {
        val stored = stored() ?: return null
        val latitude = stored.latitude ?: return null
        val longitude = stored.longitude ?: return null
        return GeoPosition(latitude, longitude, stored.locationName.ifEmpty { null })
    }

    private suspend fun stored(): WeatherInfo? {
        val raw = dataStore.data.first()[WEATHER_KEY] ?: return null
        return try {
            json.decodeFromString<WeatherInfo>(raw)
        } catch (e: Exception) {
            Log.w(e, "Corrupted weather JSON in DataStore, ignoring it")
            null
        }
    }

    override suspend fun save(info: WeatherInfo) {
        dataStore.edit { it[WEATHER_KEY] = json.encodeToString(info) }
    }

    private companion object {
        val WEATHER_KEY = stringPreferencesKey("weather_last_reading")
    }
}
