package com.opensab.app.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.opensab.logging.Log
import com.opensab.logging.LogSink
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.vehicle.BodyStyle
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.VehicleProfile
import com.opensab.sab.session.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreVehicleRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun createRepository(dataStore: DataStore<Preferences> = FakePreferencesDataStore()): VehicleRepository =
        DataStoreVehicleRepository(dataStore)

    /**
     * In-memory stand-in for the Preferences DataStore.
     *
     * The repository logic is exercised against this fake rather than against a real file because
     * DataStore renames a scratch file over the target on every write, and `File.renameTo` fails on
     * Windows when the destination already exists — so a second write never completes in a JVM unit
     * test on this platform. Actual on-disk durability is covered by
     * [persistsVehiclesAcrossRepositoryInstances], which writes once per DataStore instance.
     */
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

    private fun knownVehicle(
        macAddress: String,
        displayName: String = "Honda Forza 750",
        seriesCode: String = "RH10",
        timestamp: Long = 1_724_000_000_000L
    ) = KnownVehicle(
        macAddress = macAddress,
        displayName = displayName,
        vehicleInfo = VehicleInformation(
            vehicleType = 0x01,
            displayCapability = byteArrayOf(0x01, 0x02),
            switchType = SwitchType.TYPE_B,
            categoryId = 0x03,
            seriesCode = seriesCode,
            destinationId = DestinationId.EU
        ),
        lastConnectedTimestamp = timestamp
    )

    private fun vehicle(
        mac: String,
        name: String = "Honda Forza 750",
        timestamp: Long = 1_724_000_000_000L
    ) = knownVehicle(macAddress = mac, displayName = name, timestamp = timestamp)

    @Test
    fun persistsVehiclesAcrossRepositoryInstances() = runBlocking {
        val mac = "02:00:00:99:88:77"
        val storeFile = File(temporaryFolder.root, "vehicles.preferences_pb")

        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstRepository: VehicleRepository = createRepository(
            PreferenceDataStoreFactory.create(scope = firstScope) { storeFile }
        )
        firstRepository.saveVehicle(vehicle(mac))
        assertNotNull(firstRepository.getVehicle(mac))

        // Simulates a process restart: the old DataStore is torn down and a brand new one is opened
        // over the same file. DataStore forbids two live instances on one file inside a process.
        (firstScope.coroutineContext[Job] as Job).cancelAndJoin()

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondRepository: VehicleRepository = createRepository(
            PreferenceDataStoreFactory.create(scope = secondScope) { storeFile }
        )
        val reloaded = secondRepository.getVehicle(mac)

        assertNotNull("The paired motorcycle must survive a process restart", reloaded)
        assertEquals("Honda Forza 750", reloaded?.displayName)
        assertEquals(SwitchType.TYPE_B, reloaded?.vehicleInfo?.switchType)
        assertEquals(1, secondRepository.getAllVehicles().size)

        (secondScope.coroutineContext[Job] as Job).cancelAndJoin()
    }

    @Test
    fun returnsVehiclesSortedByLastConnectedDescending() = runBlocking {
        val repository: VehicleRepository = createRepository()

        repository.saveVehicle(vehicle("AA:AA:AA:AA:AA:01", name = "Oldest", timestamp = 1_000L))
        repository.saveVehicle(vehicle("AA:AA:AA:AA:AA:02", name = "Newest", timestamp = 3_000L))
        repository.saveVehicle(vehicle("AA:AA:AA:AA:AA:03", name = "Middle", timestamp = 2_000L))

        val names = repository.getAllVehicles().map { it.displayName }

        assertEquals(listOf("Newest", "Middle", "Oldest"), names)
    }

    @Test
    fun overwritesEntryWithSameMacAddress() = runBlocking {
        val mac = "02:00:00:99:88:77"
        val repository: VehicleRepository = createRepository()

        repository.saveVehicle(vehicle(mac, name = "First pairing", timestamp = 1_000L))
        repository.saveVehicle(vehicle(mac, name = "Re-paired", timestamp = 2_000L))

        assertEquals(1, repository.getAllVehicles().size)
        assertEquals("Re-paired", repository.getVehicle(mac)?.displayName)
    }

    @Test
    fun removeAndClearDeleteStoredEntries() = runBlocking {
        val first = "AA:AA:AA:AA:AA:01"
        val second = "AA:AA:AA:AA:AA:02"
        val dataStore = FakePreferencesDataStore()
        val repository: VehicleRepository = createRepository(dataStore)

        repository.saveVehicle(vehicle(first))
        repository.saveVehicle(vehicle(second))

        repository.removeVehicle(first)
        assertNull(repository.getVehicle(first))
        assertEquals(1, repository.getAllVehicles().size)

        repository.clear()
        assertEquals(0, repository.getAllVehicles().size)
        assertNull(repository.getVehicle(second))
    }

    @Test
    fun clearLeavesUnrelatedEntriesUntouched() = runBlocking {
        val dataStore = FakePreferencesDataStore()
        val repository: VehicleRepository = createRepository(dataStore)
        val unrelatedKey = stringPreferencesKey("app_setting_theme")

        dataStore.edit { it[unrelatedKey] = "dark" }
        repository.saveVehicle(vehicle("AA:AA:AA:AA:AA:01"))

        repository.clear()

        assertEquals(0, repository.getAllVehicles().size)
        assertEquals("dark", dataStore.data.first()[unrelatedKey])
    }

    @Test
    fun vehiclesFlowReEmitsSortedAfterEveryWrite() = runTest {
        val repository: VehicleRepository = createRepository()

        repository.vehiclesFlow.test {
            assertEquals(emptyList<KnownVehicle>(), awaitItem())

            repository.saveVehicle(vehicle("AA:AA:AA:AA:AA:01", name = "Older", timestamp = 1_000L))
            assertEquals(listOf("Older"), awaitItem().map { it.displayName })

            repository.saveVehicle(vehicle("AA:AA:AA:AA:AA:02", name = "Newer", timestamp = 3_000L))
            assertEquals(
                "The stream is ordered like getAllVehicles, most recently connected first",
                listOf("Newer", "Older"),
                awaitItem().map { it.displayName }
            )

            repository.removeVehicle("AA:AA:AA:AA:AA:02")
            assertEquals(listOf("Older"), awaitItem().map { it.displayName })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun vehiclesFlowSkipsCorruptEntriesLikeGetAllVehicles() = runTest {
        val dataStore = FakePreferencesDataStore()
        val repository: VehicleRepository = createRepository(dataStore)

        repository.saveVehicle(vehicle("AA:AA:AA:AA:AA:01"))
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("vehicle_AA:AA:AA:AA:AA:99")] = "{ truncated json"
        }

        assertEquals(
            listOf("AA:AA:AA:AA:AA:01"),
            repository.vehiclesFlow.first().map { it.macAddress }
        )
    }

    @Test
    fun ignoresCorruptEntriesInsteadOfCrashing() = runBlocking {
        val healthy = "AA:AA:AA:AA:AA:01"
        val dataStore = FakePreferencesDataStore()
        val repository: VehicleRepository = createRepository(dataStore)

        repository.saveVehicle(vehicle(healthy))
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("vehicle_AA:AA:AA:AA:AA:99")] = "{ truncated json"
        }

        val all = repository.getAllVehicles()

        assertEquals(1, all.size)
        assertEquals(healthy, all.first().macAddress)
        assertNull(repository.getVehicle("AA:AA:AA:AA:AA:99"))
    }

    @Test
    fun theProfileIsMergedIntoTheVehicle() = runTest {
        val mac = "02:00:00:11:22:33"
        val repository: VehicleRepository = createRepository()
        repository.saveVehicle(vehicle(mac))

        repository.saveProfile(mac, VehicleProfile("Rebelde", BodyStyle.CUSTOM, ClusterDisplay.SEGMENT))

        val v = repository.getVehicle(mac)!!
        assertEquals("Rebelde", v.displayName)
        assertEquals(BodyStyle.CUSTOM, v.bodyStyle)
        assertEquals(ClusterDisplay.SEGMENT, v.clusterDisplay)
        assertEquals(v, repository.vehiclesFlow.first().single())
    }

    @Test
    fun aVehicleWithoutAProfileKeepsItsStoredName() = runTest {
        val mac = "02:00:00:11:22:34"
        val repository: VehicleRepository = createRepository()
        repository.saveVehicle(vehicle(mac, name = "CB650R"))

        val v = repository.getVehicle(mac)!!
        assertEquals("CB650R", v.displayName)
        assertEquals(ClusterDisplay.COLOR, v.clusterDisplay)
    }

    @Test
    fun aProfileWithoutANameKeepsTheStoredName() = runTest {
        val mac = "02:00:00:11:22:35"
        val repository: VehicleRepository = createRepository()
        repository.saveVehicle(vehicle(mac, name = "CB650R"))

        repository.saveProfile(mac, VehicleProfile(name = null, clusterDisplay = ClusterDisplay.SEGMENT))

        assertEquals("CB650R", repository.getVehicle(mac)!!.displayName)
    }

    @Test
    fun updateProfileIsANoOpWithoutAnIdentity() = runTest {
        val repository: VehicleRepository = createRepository()

        assertFalse(repository.updateProfile("02:00:00:00:00:99", VehicleProfile("X")))
        assertNull(repository.getProfile("02:00:00:00:00:99"))
    }

    @Test
    fun anOrphanProfileDoesNotListAVehicle() = runTest {
        val repository: VehicleRepository = createRepository()

        repository.saveProfile("02:00:00:00:00:98", VehicleProfile(clusterDisplay = ClusterDisplay.SEGMENT))

        assertEquals(emptyList<KnownVehicle>(), repository.vehiclesFlow.first())
    }

    @Test
    fun forgettingAVehicleForgetsItsProfile() = runTest {
        val mac = "02:00:00:11:22:36"
        val repository: VehicleRepository = createRepository()
        repository.saveVehicle(vehicle(mac))
        repository.saveProfile(mac, VehicleProfile("X"))

        repository.removeVehicle(mac)

        assertNull(repository.getProfile(mac))
    }

    @Test
    fun savingTheIdentityDoesNotTouchTheProfile() = runTest {
        val mac = "02:00:00:11:22:37"
        val repository: VehicleRepository = createRepository()
        repository.saveProfile(mac, VehicleProfile("Rebelde"))

        repository.saveVehicle(vehicle(mac, name = "Honda 0583"))

        assertEquals("Rebelde", repository.getVehicle(mac)!!.displayName)
    }

    @Test
    fun clearForgetsAnOrphanProfileToo() = runTest {
        val repository: VehicleRepository = createRepository()

        repository.saveProfile("02:00:00:00:00:97", VehicleProfile(clusterDisplay = ClusterDisplay.SEGMENT))
        repository.clear()

        assertNull(repository.getProfile("02:00:00:00:00:97"))
    }

    @Test
    fun profileKeysAreNotReadAsIdentities() = runBlocking {
        val dataStore = FakePreferencesDataStore()
        val repository: VehicleRepository = createRepository(dataStore)
        val mac = "02:00:00:11:22:38"
        val warnings = mutableListOf<String?>()
        val sink = LogSink { priority, _, message, _ -> if (priority == Log.WARN) warnings += message }
        Log.install(sink)

        try {
            repository.saveVehicle(vehicle(mac))
            repository.saveProfile(mac, VehicleProfile("Rebelde"))

            val all = repository.getAllVehicles()

            assertEquals(1, all.size)
            assertEquals("Rebelde", all.single().displayName)
            // A profile key scanned as an identity would fail to decode as a KnownVehicle and warn.
            assertEquals(emptyList<String?>(), warnings)
        } finally {
            Log.uninstall(sink)
        }
    }
}
