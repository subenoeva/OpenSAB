package com.opensab.app.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.InboundCallMode
import com.opensab.domain.settings.InboundMessageMode
import com.opensab.domain.settings.TemperatureUnit
import com.opensab.domain.settings.VoiceVerbosity
import com.opensab.domain.weather.WeatherConditionType
import com.opensab.domain.weather.WeatherForecastSegment
import com.opensab.domain.weather.WeatherInfo
import com.opensab.domain.weather.WmoCaption
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.DeviceInformation
import com.opensab.sab.protocol.vehicle.FirmwareVersion
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.vehicle.KnownVehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the on-disk preferences format written by the four DataStore adapters.
 *
 * The fixture at `app/src/test/resources/persistence/preferences-650c439.preferences_pb` was
 * generated once, by a temporary writer (not committed) driving the same four adapters this test
 * still reads with. This test never writes: on Windows, a second write against a real DataStore
 * file dies in `File.renameTo` because it refuses an existing destination, so each test
 * method copies the resource into its own [TemporaryFolder], opens exactly one
 * [PreferenceDataStoreFactory] instance over that copy, and only reads.
 */
class PersistenceFormatCompatibilityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val store by lazy {
        val resourceName = "persistence/preferences-650c439.preferences_pb"
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream(resourceName)) {
            "Missing test fixture resource: $resourceName"
        }
        val copy = File(temporaryFolder.root, "preferences-650c439.preferences_pb")
        resource.use { input -> copy.outputStream().use { output -> input.copyTo(output) } }
        PreferenceDataStoreFactory.create(scope = scope) { copy }
    }

    @After
    fun releaseTheStoreFile() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        Unit
    }

    @Test
    fun `the four keys written before the move are still the only keys`() = runBlocking {
        val keys = store.data.first().asMap().keys.map { it.name }.toSet()

        assertEquals(
            setOf(
                "app_settings_v1",
                "vehicle_02:00:00:00:00:01",
                "weather_last_reading",
                "favorite_media_package"
            ),
            keys
        )
    }

    @Test
    fun `settings written before the move read back field for field`() = runBlocking {
        val settings = DataStoreSettingsRepository(store).getSettings()

        assertEquals(FIXTURE_SETTINGS, settings)
    }

    @Test
    fun `a vehicle written before the move reads back`() = runBlocking {
        val vehicle = DataStoreVehicleRepository(store).getVehicle("02:00:00:00:00:01")

        assertEquals(FIXTURE_VEHICLE, vehicle)
    }

    @Test
    fun `a weather reading and a favourite player written before the move read back`() = runBlocking {
        val weather = DataStoreWeatherCache(store, nowMillis = { 1_700_000_000_000L }).load()
        val favoritePackage = DataStoreFavoriteMediaRepository(store).favoritePackage.first()

        assertEquals(FIXTURE_WEATHER, weather)
        assertEquals("com.example.player", favoritePackage)
    }

    /**
     * Guards against a renamed JSON key that decoding alone would hide: most [AppSettings] fields
     * carry defaults and decoding ignores unknown keys, so a field renamed in the data class would
     * decode to its default and could still equal [FIXTURE_SETTINGS] by coincidence. Re-encoding the
     * decoded value with the adapter's own [Json] configuration and comparing key sets instead of
     * values catches the rename even when the values line up.
     */
    @Test
    fun `the stored settings JSON has exactly the keys today's adapter would write`() = runBlocking {
        val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val raw = store.data.first()[stringPreferencesKey("app_settings_v1")]
        requireNotNull(raw) { "Fixture is missing app_settings_v1" }

        val decoded = DataStoreSettingsRepository(store).getSettings()
        val reencoded = settingsJson.encodeToString(decoded)

        assertEquals(
            keyPaths(settingsJson.parseToJsonElement(reencoded)),
            keyPaths(settingsJson.parseToJsonElement(raw)) - RETIRED_KEYS + SETTINGS_KEYS_ADDED_AFTER_FIXTURE
        )
    }

    /**
     * A retired key stays readable off the fixture (covered above, since decoding ignores unknown
     * keys) but the adapter must not put it back on the next write.
     */
    @Test fun aRetiredSettingsKeyIsIgnoredOnReadAndNeverWritten() = runBlocking {
        val written = keyPaths(Json.parseToJsonElement(storedSettingsJsonAfterOneWrite()))
        assertTrue(RETIRED_KEYS.none { it in written })
    }

    private suspend fun storedSettingsJsonAfterOneWrite(): String {
        val store = InMemorySettingsDataStore()
        DataStoreSettingsRepository(store).updateSettings { it }
        return requireNotNull(store.data.first()[stringPreferencesKey("app_settings_v1")])
    }

    private class InMemorySettingsDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences = transform(state.value).also { state.value = it }
    }

    /** The settings key-set pin, repeated for [WeatherInfo]'s own [Json] configuration. */
    @Test
    fun `the stored weather JSON has exactly the keys today's adapter would write`() = runBlocking {
        val weatherJson = Json { ignoreUnknownKeys = true }
        val raw = store.data.first()[stringPreferencesKey("weather_last_reading")]
        requireNotNull(raw) { "Fixture is missing weather_last_reading" }

        val decoded = requireNotNull(DataStoreWeatherCache(store, nowMillis = { 1_700_000_000_000L }).load())
        val reencoded = weatherJson.encodeToString(decoded)

        assertEquals(
            keyPaths(weatherJson.parseToJsonElement(reencoded)),
            keyPaths(weatherJson.parseToJsonElement(raw))
        )
    }

    /**
     * Every key path in [element], dotted for a nested object and `[]`-suffixed for an array's
     * elements (position does not matter for a rename, only which names exist).
     */
    private fun keyPaths(element: JsonElement, prefix: String = ""): Set<String> = when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) ->
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            setOf(path) + keyPaths(value, path)
        }.toSet()
        is JsonArray -> element.flatMap { keyPaths(it, "$prefix[]") }.toSet()
        else -> emptySet()
    }

    private companion object {
        /**
         * Fields added to [AppSettings] after the fixture was frozen. A new field is a key the
         * fixture cannot have; naming it here keeps the pin honest about renames, which show up as
         * a key missing from the fixture plus one this list does not name.
         */
        val SETTINGS_KEYS_ADDED_AFTER_FIXTURE = setOf(
            "rootMenuOrder",
            "rootMenuHidden",
            "sessionLogLimitMb",
            "muteAction",
            "verboseLoggingChoice",
            "weatherRefreshMinutes",
            "sabModeWaitSeconds",
            "sabModeRerequestOnHuMode",
            "projectWithoutSabModeGrant"
        )

        /** Keys the fixture carries that the current adapter no longer writes. */
        val RETIRED_KEYS = setOf("clusterProfile", "distanceUnit", "verboseLogging")

        val FIXTURE_SETTINGS = AppSettings(
            inboundCallMode = InboundCallMode.IGNORE,
            inboundMessageMode = InboundMessageMode.READ_DIRECTLY,
            voiceVerbosity = VoiceVerbosity.ADVANCED,
            quickReplies = listOf("On my way", "Line one\nline two"),
            replySentLingerSeconds = 5,
            temperatureUnit = TemperatureUnit.FAHRENHEIT,
            permissionsIntroSeen = true
        )

        val FIXTURE_VEHICLE = KnownVehicle(
            macAddress = "02:00:00:00:00:01",
            displayName = "Honda Forza 750",
            vehicleInfo = VehicleInformation(
                vehicleType = 0x01,
                displayCapability = byteArrayOf(0x01, 0x02),
                switchType = SwitchType.TYPE_B,
                categoryId = 0x03,
                seriesCode = "RH10",
                destinationId = DestinationId.EU
            ),
            deviceInfo = DeviceInformation(
                manufacturerName = "Honda",
                modelNumber = "BTU-01",
                serialNumber = "SN-0001",
                hardwareRevision = "HW1",
                firmwareRevision = "FW2",
                softwareRevision = "SW3"
            ),
            firmwareVersion = FirmwareVersion("01", "07"),
            lastConnectedTimestamp = 1_700_000_000_000L
        )

        val FIXTURE_WEATHER = WeatherInfo(
            locationName = "Testville",
            temperature = 21,
            caption = WmoCaption.CLEAR,
            type = WeatherConditionType.SUNNY,
            hourly = listOf(
                WeatherForecastSegment(
                    timestamp = 1_700_003_600_000L,
                    temperature = 20,
                    caption = WmoCaption.PARTLY_CLOUDY,
                    type = WeatherConditionType.SUN_AND_CLOUD
                ),
                WeatherForecastSegment(
                    timestamp = 1_700_007_200_000L,
                    temperature = 19,
                    caption = WmoCaption.OVERCAST,
                    type = WeatherConditionType.CLOUDY
                )
            ),
            timestamp = 1_700_000_000_000L
        )
    }
}
