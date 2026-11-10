package com.opensab.app.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.InboundCallMode
import com.opensab.domain.settings.SettingsRepository
import com.opensab.logging.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** [SettingsRepository] backed by DataStore Preferences with atomic JSON serialization. */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val settingsFlow: Flow<AppSettings> = dataStore.data.map { preferences ->
        val raw = preferences[SETTINGS_KEY]
        decodeSettings(raw)
    }

    override suspend fun getSettings(): AppSettings {
        val raw = dataStore.data.first()[SETTINGS_KEY]
        return decodeSettings(raw)
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            val current = decodeSettings(preferences[SETTINGS_KEY])
            val updated = transform(current)
            preferences[SETTINGS_KEY] = json.encodeToString(updated)
        }
        Log.d("Updated application settings")
    }

    /**
     * Removes the key rather than writing the defaults over it, which is why a reset leaves the
     * store holding every other key and one entry lighter — from the file size alone, the same
     * shape as settings that vanished. So the line below says how much was dropped: this path is
     * the only one that removes this key, and it has to be recognisable in a log.
     */
    override suspend fun resetToDefaults() {
        // Read outside the transform: `edit` may replay it, and a log line that repeats would be
        // worse than none.
        val dropped = dataStore.data.first()[SETTINGS_KEY]
        dataStore.edit { preferences ->
            preferences.remove(SETTINGS_KEY)
        }
        Log.i(
            "Reset application settings to defaults, removing app_settings_v1 (%d bytes of JSON)",
            dropped?.length ?: 0
        )
    }

    private fun decodeSettings(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings()
        return try {
            val normalized = normalizeLegacyJson(raw)
            json.decodeFromString<AppSettings>(normalized)
        } catch (e: Exception) {
            Log.w(e, "Corrupted settings JSON in DataStore, resetting to default")
            AppSettings()
        }
    }

    /**
     * Normalizes legacy JSON schemas containing `interceptIncomingCalls` without `inboundCallMode`.
     */
    private fun normalizeLegacyJson(raw: String): String {
        return try {
            val element = json.parseToJsonElement(raw)
            if (element is JsonObject && !element.containsKey("inboundCallMode") && element.containsKey("interceptIncomingCalls")) {
                val intercept = element["interceptIncomingCalls"]?.jsonPrimitive?.booleanOrNull
                if (intercept != null) {
                    val mode = if (intercept) InboundCallMode.ALERT.name else InboundCallMode.IGNORE.name
                    val mutable = element.toMutableMap()
                    mutable["inboundCallMode"] = JsonPrimitive(mode)
                    JsonObject(mutable).toString()
                } else {
                    raw
                }
            } else {
                raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    private companion object {
        val SETTINGS_KEY = stringPreferencesKey("app_settings_v1")
    }
}
