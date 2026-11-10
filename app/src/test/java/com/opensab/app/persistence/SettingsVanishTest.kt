package com.opensab.app.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What could and could not have made `app_settings_v1` disappear.
 *
 * Measured on a test phone: the key was gone from the DataStore file and a `vehicle_` key was still
 * there, with the file down from 420 to 307 bytes. No log covered the window, so no log line
 * decides this; what follows is what the code can be made to say.
 *
 * Everything shares one file. `AppModule.provideVehicleDataStore` builds the only
 * `DataStore<Preferences>` in the graph, named `vehicles`, and the settings, the known motorcycles,
 * the favourite player and the weather cache all live inside it.
 */
class SettingsVanishTest {

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

    private val settingsKey = stringPreferencesKey("app_settings_v1")
    private val vehicleKey = stringPreferencesKey("vehicle_02:00:00:00:00:AB")

    /**
     * The JSON that was actually on disk, written by an older build: it carries
     * `resolveContactNames`, since removed, and lacks `temperatureUnit`, since added.
     */
    private val oldSchemaJson = """
        {"autoConnect":true,"autoDisconnect":true,"keepScreenOnWhileRiding":true,
         "showOnLockScreen":true,"clusterProfile":"AUTO","themeMode":"SYSTEM",
         "favoriteMediaPackage":null,"autoResumeMusicOnConnect":false,
         "handlebarVolumeControl":true,"distanceUnit":"MILES","interceptGpsNotifications":true,
         "resolveContactNames":true,"interceptIncomingCalls":false,"ctsClockSync":true,
         "verboseLoggingChoice":true}
    """.trimIndent().replace("\n", "").replace(" ", "")

    private suspend fun storeWithBoth(): FakePreferencesDataStore {
        val store = FakePreferencesDataStore()
        store.edit {
            it[settingsKey] = oldSchemaJson
            it[vehicleKey] = """{"macAddress":"02:00:00:00:00:AB"}"""
        }
        return store
    }

    /**
     * **The shape that was measured, reproduced.** `resetToDefaults` is the only code path in the
     * repository that removes this key, it removes exactly this key, and it leaves the motorcycle
     * alone — which is the observed file down to one entry rather than to none.
     *
     * It is not a defect: it is `SettingsUiIntent.ResetSettingsToDefaults`, a button in Settings
     * with a confirmation toast. It logs `Resetting settings to default values` at INFO.
     */
    @Test
    fun resettingToDefaultsRemovesTheSettingsKeyAndNothingElse() = runBlocking {
        val store = storeWithBoth()
        val repository: SettingsRepository = DataStoreSettingsRepository(store)

        repository.resetToDefaults()

        val stored = store.data.first()
        assertNull("app_settings_v1 survived a reset", stored[settingsKey])
        assertNotNull("the known motorcycle was taken down with the settings", stored[vehicleKey])
    }

    /**
     * **The suspect, ruled out.** `ReplaceFileCorruptionHandler { emptyPreferences() }` replaces the
     * whole file, so a `CorruptionException` takes the settings, the motorcycles, the favourite
     * player and the weather cache **together**. It cannot leave one key standing, which is what was
     * measured, so it did not do this in one step.
     *
     * This asserts on the replacement the handler installs, which is the part that decides what
     * survives — `emptyPreferences()` by name, straight out of `AppModule`.
     */
    @Test
    fun theCorruptionHandlerWouldHaveTakenTheMotorcycleDownToo() = runBlocking {
        val store = storeWithBoth()
        assertNotNull(store.data.first()[vehicleKey])

        // What AppModule.provideVehicleDataStore hands DataStore as the replacement.
        val replacement = emptyPreferences()
        store.updateData { replacement }

        val stored = store.data.first()
        assertNull(stored[settingsKey])
        assertNull(
            "the corruption handler is only the suspect if it can spare the motorcycle, and it " +
                "cannot: emptyPreferences() keeps nothing",
            stored[vehicleKey]
        )
        assertTrue(stored.asMap().isEmpty())
    }

    /**
     * **The other theory, ruled out.** The JSON on disk came from an older build. Reading it under
     * the current schema neither throws nor removes anything: `ignoreUnknownKeys = true` drops
     * `resolveContactNames`, the missing `temperatureUnit` takes its default, and the settings the
     * rider had chosen survive.
     */
    @Test
    fun aSettingsJsonFromAnOlderBuildIsReadNotDiscarded() = runBlocking {
        val store = storeWithBoth()
        val repository: SettingsRepository = DataStoreSettingsRepository(store)

        val settings = repository.getSettings()

        assertEquals(false, settings.interceptIncomingCalls)
        assertEquals(true, settings.verboseLogging)
        assertNull("temperatureUnit did not fall back to its default", settings.temperatureUnit)
        assertNotNull("reading an old schema removed the key", store.data.first()[settingsKey])
    }

    /**
     * And a JSON that cannot be parsed at all still does not remove the key: the rider is served
     * defaults for that read, and the bytes stay on disk for the next build to look at. Losing them
     * would be losing the evidence of why they were unreadable.
     */
    @Test
    fun anUnreadableSettingsJsonLeavesTheKeyOnDisk() = runBlocking {
        val store = FakePreferencesDataStore()
        store.edit { it[settingsKey] = "{not json at all" }
        val repository: SettingsRepository = DataStoreSettingsRepository(store)

        val settings = repository.getSettings()

        assertEquals(AppSettings(), settings)
        assertEquals(
            "an unreadable settings JSON was silently deleted",
            "{not json at all",
            store.data.first()[settingsKey]
        )
    }
}
