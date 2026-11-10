package com.opensab.app.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.InboundCallMode
import com.opensab.domain.settings.InboundMessageMode
import com.opensab.domain.settings.MuteAction
import com.opensab.domain.settings.SettingsRepository
import com.opensab.domain.settings.TemperatureUnit
import com.opensab.domain.settings.ThemeMode
import com.opensab.domain.settings.Tunable
import com.opensab.domain.settings.VoiceVerbosity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class DataStoreSettingsRepositoryTest {

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

    @Test
    fun shouldEmitDefaultSettingsInitially() = runBlocking {
        val repository: SettingsRepository = DataStoreSettingsRepository(FakePreferencesDataStore())
        val settings = repository.settingsFlow.first()

        assertTrue(settings.autoConnect)
        assertTrue(settings.autoDisconnect)
        assertTrue(settings.keepScreenOnWhileRiding)
        assertTrue(settings.showOnLockScreen)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertTrue(settings.ctsClockSync)
        assertNull(settings.verboseLogging)
    }

    @Test
    fun shouldPersistUpdatedSettings() = runBlocking {
        val repository: SettingsRepository = DataStoreSettingsRepository(FakePreferencesDataStore())

        repository.updateSettings { current ->
            current.copy(
                autoConnect = false,
                themeMode = ThemeMode.DARK,
                verboseLogging = true
            )
        }

        val updated = repository.getSettings()
        assertFalse(updated.autoConnect)
        assertEquals(ThemeMode.DARK, updated.themeMode)
        assertEquals(true, updated.verboseLogging)
    }

    /**
     * The unset temperature unit is a third state, not a missing value: it has to survive a write
     * of something else, or the first settings change would freeze the country default forever.
     */
    @Test
    fun shouldRoundTripTheTemperatureUnitIncludingTheUnsetState() = runBlocking {
        val repository: SettingsRepository = DataStoreSettingsRepository(FakePreferencesDataStore())
        assertEquals(null, repository.getSettings().temperatureUnit)

        repository.updateSettings { it.copy(verboseLogging = true) }
        assertEquals(null, repository.getSettings().temperatureUnit)

        repository.updateSettings { it.copy(temperatureUnit = TemperatureUnit.FAHRENHEIT) }
        assertEquals(TemperatureUnit.FAHRENHEIT, repository.getSettings().temperatureUnit)
    }

    @Test
    fun theOldVerboseLoggingKeyIsDroppedSoTheBuildDefaultApplies() = runBlocking {
        val store = FakePreferencesDataStore()
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("app_settings_v1"), """{"autoConnect":false,"verboseLogging":false}""")
            }
        }

        val settings = DataStoreSettingsRepository(store).getSettings()

        assertNull(settings.verboseLogging)
        assertFalse(settings.autoConnect)
    }

    @Test
    fun aVerboseLoggingChoiceSurvivesARoundTrip() = runBlocking {
        val repository = DataStoreSettingsRepository(FakePreferencesDataStore())
        repository.updateSettings { it.copy(verboseLogging = false) }
        assertEquals(false, repository.getSettings().verboseLogging)
    }

    @Test
    fun shouldResetToDefaultSettings() = runBlocking {
        val repository: SettingsRepository = DataStoreSettingsRepository(FakePreferencesDataStore())

        repository.updateSettings { current ->
            current.copy(autoConnect = false, themeMode = ThemeMode.DARK)
        }
        assertFalse(repository.getSettings().autoConnect)

        repository.resetToDefaults()
        val reset = repository.getSettings()
        assertTrue(reset.autoConnect)
        assertEquals(ThemeMode.SYSTEM, reset.themeMode)
    }

    // -------------------------------------------------------------------------------------------
    // The rider's own quick replies
    // -------------------------------------------------------------------------------------------

    /**
     * A numbered list whose order is not kept is not a list.
     *
     * A `stringSetPreferencesKey` would lose the order, but this repository stores the whole of
     * [AppSettings] as one JSON document, so a `List<String>` is a JSON array and keeps its order
     * by construction. It is checked all the same, because a store that reorders is a store the
     * rider cannot trust with a numbered list.
     */
    @Test
    fun `should survive a restart with the order of the quick replies intact`() = runBlocking {
        val store = FakePreferencesDataStore()
        val chosen = listOf("On my way", "Can't talk", "Call you later")
        DataStoreSettingsRepository(store).updateSettings { it.copy(quickReplies = chosen) }

        // A second repository over the same store is what a restart looks like from here.
        val afterRestart = DataStoreSettingsRepository(store).getSettings()

        assertEquals(chosen, afterRestart.quickReplies)
    }

    /** Empty is "the rider has not touched this", never a list holding one empty string. */
    @Test
    fun `should read an untouched quick reply list as empty`() = runBlocking {
        val settings = DataStoreSettingsRepository(FakePreferencesDataStore()).getSettings()

        assertEquals(emptyList<String>(), settings.quickReplies)
    }

    /**
     * The trap a separator character would have set: a reply the rider typed a newline into would
     * have come back as two. Nothing splits here, and this is what says so.
     */
    @Test
    fun `should keep a reply that contains a newline in one piece`() = runBlocking {
        val store = FakePreferencesDataStore()
        val chosen = listOf("Two\nlines", "Plain")
        DataStoreSettingsRepository(store).updateSettings { it.copy(quickReplies = chosen) }

        assertEquals(chosen, DataStoreSettingsRepository(store).getSettings().quickReplies)
    }

    @Test
    fun `should persist how long Sent holds the screen`() = runBlocking {
        val store = FakePreferencesDataStore()
        DataStoreSettingsRepository(store).updateSettings { it.copy(replySentLingerSeconds = 8) }

        assertEquals(8, DataStoreSettingsRepository(store).getSettings().replySentLingerSeconds)
    }

    /**
     * Stored as written, clamped as read. The repository is not where the range lives — a value
     * out of range has to survive a round trip so that `Tunable.REPLY_SENT_LINGER` is the single place
     * that decides what it means.
     */
    @Test
    fun `should store a linger outside the range and leave the clamping to the reader`() = runBlocking {
        val store = FakePreferencesDataStore()
        DataStoreSettingsRepository(store).updateSettings { it.copy(replySentLingerSeconds = 999) }

        val stored = DataStoreSettingsRepository(store).getSettings()
        assertEquals(999, stored.replySentLingerSeconds)
        assertEquals(Tunable.REPLY_SENT_LINGER.range.last.seconds, Tunable.REPLY_SENT_LINGER.resolve(stored))
    }

    @Test
    fun `should persist every tunable as written, in range or not`() = runBlocking {
        for (tunable in Tunable.entries) {
            val store = FakePreferencesDataStore()
            DataStoreSettingsRepository(store).updateSettings { tunable.write(it, 999) }

            val stored = DataStoreSettingsRepository(store).getSettings()
            assertEquals("$tunable", 999, tunable.storedIn(stored))
            assertEquals("$tunable", tunable.range.last, tunable.valueIn(stored))
        }
    }

    /** A document from before the tunables existed carries none of their keys. */
    @Test
    fun `should read the tunables of an older document as their defaults`() = runBlocking {
        val store = FakePreferencesDataStore()
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("app_settings_v1"), """{"autoConnect":false}""")
            }
        }

        val settings = DataStoreSettingsRepository(store).getSettings()

        for (tunable in Tunable.entries) {
            assertEquals("$tunable", tunable.default, tunable.valueIn(settings))
        }
    }

    /**
     * A rider who has never opened the app has not seen the permissions screen, so the flag has to
     * default to "not seen" or the screen's one automatic showing never happens.
     */
    @Test
    fun `should default the intro flag to not seen`() {
        assertFalse(AppSettings().permissionsIntroSeen)
    }

    @Test
    fun `should survive a restart with the intro flag intact`() = runBlocking {
        val store = FakePreferencesDataStore()
        DataStoreSettingsRepository(store).updateSettings { it.copy(permissionsIntroSeen = true) }

        // A second repository over the same store is what a restart looks like from here.
        assertTrue(DataStoreSettingsRepository(store).getSettings().permissionsIntroSeen)
    }

    /**
     * The upgrade path. `AppSettings` is stored as one JSON document, so a build that did not know
     * about this field wrote a document without it; `ignoreUnknownKeys` reads that back without
     * throwing, the missing field takes its default, and nothing else in the document is lost.
     */
    @Test
    fun `should read a document written before the flag existed`() = runBlocking {
        val store = FakePreferencesDataStore()
        val legacy = """{"autoConnect":false,"replySentLingerSeconds":7,"retiredKey":"gone"}"""
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("app_settings_v1"), legacy)
            }
        }

        val settings = DataStoreSettingsRepository(store).getSettings()

        assertFalse("a document without the key means the intro has not been seen", settings.permissionsIntroSeen)
        assertFalse("and nothing else in it may be lost on the way", settings.autoConnect)
        assertEquals(7, settings.replySentLingerSeconds)
    }

    // -------------------------------------------------------------------------------------------
    // Voice verbosity
    // -------------------------------------------------------------------------------------------

    @Test
    fun `should default voice verbosity to STANDARD`() {
        assertEquals(VoiceVerbosity.STANDARD, AppSettings().voiceVerbosity)
    }

    @Test
    fun `should persist and round-trip every voice verbosity mode`() = runBlocking {
        val store = FakePreferencesDataStore()
        val repo = DataStoreSettingsRepository(store)

        for (mode in VoiceVerbosity.entries) {
            repo.updateSettings { it.copy(voiceVerbosity = mode) }
            val retrieved = DataStoreSettingsRepository(store).getSettings()
            assertEquals(mode, retrieved.voiceVerbosity)
        }
    }

    @Test
    fun `should read legacy document without voice verbosity as STANDARD`() = runBlocking {
        val store = FakePreferencesDataStore()
        val legacy = """{"autoConnect":false,"permissionsIntroSeen":true}"""
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("app_settings_v1"), legacy)
            }
        }

        val settings = DataStoreSettingsRepository(store).getSettings()
        assertEquals(VoiceVerbosity.STANDARD, settings.voiceVerbosity)
        assertFalse(settings.autoConnect)
        assertTrue(settings.permissionsIntroSeen)
    }

    // -------------------------------------------------------------------------------------------
    // Inbound modes and legacy migration
    // -------------------------------------------------------------------------------------------

    @Test
    fun `should default inbound modes to PROMPT and ALERT`() {
        val settings = AppSettings()
        assertEquals(InboundMessageMode.PROMPT, settings.inboundMessageMode)
        assertEquals(InboundCallMode.ALERT, settings.inboundCallMode)
    }

    @Test
    fun `should persist and round-trip every inbound mode`() = runBlocking {
        val store = FakePreferencesDataStore()
        val repo = DataStoreSettingsRepository(store)

        for (msgMode in InboundMessageMode.entries) {
            for (callMode in InboundCallMode.entries) {
                repo.updateSettings { it.copy(inboundMessageMode = msgMode, inboundCallMode = callMode) }
                val retrieved = DataStoreSettingsRepository(store).getSettings()
                assertEquals(msgMode, retrieved.inboundMessageMode)
                assertEquals(callMode, retrieved.inboundCallMode)
            }
        }
    }

    @Test
    fun `should decode legacy JSON with interceptIncomingCalls false as IGNORE`() = runBlocking {
        val store = FakePreferencesDataStore()
        val legacy = """{"autoConnect":true,"interceptIncomingCalls":false}"""
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("app_settings_v1"), legacy)
            }
        }

        val settings = DataStoreSettingsRepository(store).getSettings()
        assertEquals(InboundCallMode.IGNORE, settings.inboundCallMode)
        assertFalse(settings.interceptIncomingCalls)
    }

    @Test
    fun `should decode legacy JSON with interceptIncomingCalls true as ALERT`() = runBlocking {
        val store = FakePreferencesDataStore()
        val legacy = """{"autoConnect":true,"interceptIncomingCalls":true}"""
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("app_settings_v1"), legacy)
            }
        }

        val settings = DataStoreSettingsRepository(store).getSettings()
        assertEquals(InboundCallMode.ALERT, settings.inboundCallMode)
        assertTrue(settings.interceptIncomingCalls)
    }

    @Test
    fun `should prioritize explicit inboundCallMode over legacy interceptIncomingCalls`() = runBlocking {
        val store = FakePreferencesDataStore()
        val legacy = """{"interceptIncomingCalls":false,"inboundCallMode":"ALERT"}"""
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("app_settings_v1"), legacy)
            }
        }

        val settings = DataStoreSettingsRepository(store).getSettings()
        assertEquals(InboundCallMode.ALERT, settings.inboundCallMode)
    }

    @Test
    fun `should decode empty JSON with default inbound modes`() = runBlocking {
        val store = FakePreferencesDataStore()
        val legacy = """{}"""
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("app_settings_v1"), legacy)
            }
        }

        val settings = DataStoreSettingsRepository(store).getSettings()
        assertEquals(InboundMessageMode.PROMPT, settings.inboundMessageMode)
        assertEquals(InboundCallMode.ALERT, settings.inboundCallMode)
    }

    /** A JSON written before the root menu layout existed decodes, and says "untouched". */
    @Test
    fun `settings stored before the root menu layout decode with an empty layout`() = runBlocking {
        val store = FakePreferencesDataStore()
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("app_settings_v1"), """{"autoConnect":false}""")
            }
        }

        val settings = DataStoreSettingsRepository(store).getSettings()
        assertFalse(settings.autoConnect)
        assertEquals(emptyList<String>(), settings.rootMenuOrder)
        assertEquals(emptySet<String>(), settings.rootMenuHidden)
    }

    /** An entry name from a future version must not reset every other setting. */
    @Test
    fun `an unknown root menu name keeps every other setting`() = runBlocking {
        val repository = DataStoreSettingsRepository(FakePreferencesDataStore())
        repository.updateSettings {
            it.copy(autoConnect = false, rootMenuOrder = listOf("RADIO", "MUSIC"), rootMenuHidden = setOf("RADIO"))
        }

        val settings = repository.getSettings()
        assertFalse(settings.autoConnect)
        assertEquals(listOf("RADIO", "MUSIC"), settings.rootMenuOrder)
        assertEquals(setOf("RADIO"), settings.rootMenuHidden)
    }

    @Test
    fun aStoredDistanceUnitKeyIsIgnoredAndTheRestSurvives() = runBlocking {
        val store = FakePreferencesDataStore()
        val stored = """{"autoConnect":false,"distanceUnit":"MILES","themeMode":"DARK","ctsClockSync":false}"""
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("app_settings_v1"), stored)
            }
        }

        val settings = DataStoreSettingsRepository(store).getSettings()

        assertFalse(settings.autoConnect)
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertFalse(settings.ctsClockSync)
    }

    @Test
    fun aStoredSettingsWithoutTheLimitReadsFifty() = runBlocking {
        val store = FakePreferencesDataStore()
        val stored = """{"autoConnect":true,"autoDisconnect":true,"keepScreenOnWhileRiding":true,""" +
            """"themeMode":"DARK","distanceUnit":"KILOMETERS","ctsClockSync":true,""" +
            """"permissionsIntroSeen":true,"verboseLoggingChoice":true}"""
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("app_settings_v1"), stored)
            }
        }

        val settings = DataStoreSettingsRepository(store).getSettings()

        assertEquals(50, settings.sessionLogLimitMb)
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(true, settings.verboseLogging)
    }

    @Test
    fun legacyJsonWithoutMuteActionDecodesToPauseMusic() = runBlocking {
        val store = FakePreferencesDataStore()
        val legacyJson = """{"autoConnect": true}"""
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("app_settings_v1"), legacyJson)
            }
        }
        val repo = DataStoreSettingsRepository(store)
        val settings = repo.getSettings()
        assertEquals(MuteAction.PAUSE_MUSIC, settings.muteAction)
    }

    @Test
    fun updatingMuteActionPersistsAndRecoversMuteAll() = runBlocking {
        val repo = DataStoreSettingsRepository(FakePreferencesDataStore())
        repo.updateSettings { it.copy(muteAction = MuteAction.MUTE_ALL) }
        assertEquals(MuteAction.MUTE_ALL, repo.getSettings().muteAction)
    }
}
