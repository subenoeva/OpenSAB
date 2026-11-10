package com.opensab.sab.session.vehicle

import app.cash.turbine.test
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleRepositoryTest {

    private val vehicleForProfileTests = KnownVehicle(
        macAddress = "02:00:00:11:22:33",
        displayName = "Honda CB650R",
        vehicleInfo = VehicleInformation(
            vehicleType = 0x01,
            displayCapability = byteArrayOf(0x01, 0x02),
            switchType = SwitchType.TYPE_B,
            categoryId = 0x01,
            seriesCode = "RH10",
            destinationId = DestinationId.EU
        )
    )

    @Test
    fun theProfileIsMergedIntoTheVehicle() = runTest {
        val repository: VehicleRepository = InMemoryVehicleRepository()
        repository.saveVehicle(vehicleForProfileTests)

        repository.saveProfile(
            vehicleForProfileTests.macAddress,
            VehicleProfile("Rebelde", BodyStyle.CUSTOM, ClusterDisplay.SEGMENT)
        )

        val v = repository.getVehicle(vehicleForProfileTests.macAddress)!!
        assertEquals("Rebelde", v.displayName)
        assertEquals(BodyStyle.CUSTOM, v.bodyStyle)
        assertEquals(ClusterDisplay.SEGMENT, v.clusterDisplay)
        assertEquals(v, repository.vehiclesFlow.first().single())
    }

    @Test
    fun aVehicleWithoutAProfileKeepsItsStoredName() = runTest {
        val repository: VehicleRepository = InMemoryVehicleRepository()
        repository.saveVehicle(vehicleForProfileTests.copy(displayName = "CB650R"))

        val v = repository.getVehicle(vehicleForProfileTests.macAddress)!!
        assertEquals("CB650R", v.displayName)
        assertEquals(ClusterDisplay.COLOR, v.clusterDisplay)
    }

    @Test
    fun aProfileWithoutANameKeepsTheStoredName() = runTest {
        val repository: VehicleRepository = InMemoryVehicleRepository()
        repository.saveVehicle(vehicleForProfileTests.copy(displayName = "CB650R"))

        repository.saveProfile(
            vehicleForProfileTests.macAddress,
            VehicleProfile(name = null, clusterDisplay = ClusterDisplay.SEGMENT)
        )

        assertEquals("CB650R", repository.getVehicle(vehicleForProfileTests.macAddress)!!.displayName)
    }

    @Test
    fun updateProfileIsANoOpWithoutAnIdentity() = runTest {
        val repository: VehicleRepository = InMemoryVehicleRepository()

        assertFalse(repository.updateProfile("02:00:00:00:00:99", VehicleProfile("X")))
        assertNull(repository.getProfile("02:00:00:00:00:99"))
    }

    @Test
    fun anOrphanProfileDoesNotListAVehicle() = runTest {
        val repository: VehicleRepository = InMemoryVehicleRepository()

        repository.saveProfile("02:00:00:00:00:98", VehicleProfile(clusterDisplay = ClusterDisplay.SEGMENT))

        assertEquals(emptyList<KnownVehicle>(), repository.vehiclesFlow.first())
    }

    @Test
    fun forgettingAVehicleForgetsItsProfile() = runTest {
        val repository: VehicleRepository = InMemoryVehicleRepository()
        repository.saveVehicle(vehicleForProfileTests)
        repository.saveProfile(vehicleForProfileTests.macAddress, VehicleProfile("X"))

        repository.removeVehicle(vehicleForProfileTests.macAddress)

        assertNull(repository.getProfile(vehicleForProfileTests.macAddress))
    }

    @Test
    fun savingTheIdentityDoesNotTouchTheProfile() = runTest {
        val repository: VehicleRepository = InMemoryVehicleRepository()
        repository.saveProfile(vehicleForProfileTests.macAddress, VehicleProfile("Rebelde"))

        repository.saveVehicle(vehicleForProfileTests.copy(displayName = "Honda 0583"))

        assertEquals("Rebelde", repository.getVehicle(vehicleForProfileTests.macAddress)!!.displayName)
    }

    @Test
    fun saveGetAndRemoveKnownVehicle() = runBlocking {
        val repository: VehicleRepository = InMemoryVehicleRepository()
        val mac = "02:00:00:99:88:77"

        val vehicle = KnownVehicle(
            macAddress = mac,
            displayName = "Honda Forza 750",
            vehicleInfo = VehicleInformation(
                vehicleType = 0x01,
                displayCapability = byteArrayOf(0x01, 0x02),
                switchType = SwitchType.TYPE_B,
                categoryId = 0x01,
                seriesCode = "RH10",
                destinationId = DestinationId.EU
            )
        )

        assertNull(repository.getVehicle(mac))
        assertEquals(0, repository.getAllVehicles().size)

        repository.saveVehicle(vehicle)
        val saved = repository.getVehicle(mac)
        assertNotNull(saved)
        assertEquals("Honda Forza 750", saved?.displayName)
        assertEquals(1, repository.getAllVehicles().size)

        repository.removeVehicle(mac)
        assertNull(repository.getVehicle(mac))
        assertEquals(0, repository.getAllVehicles().size)
    }

    @Test
    fun vehiclesFlowReEmitsOnEveryWrite() = runTest {
        val repository: VehicleRepository = InMemoryVehicleRepository()
        val first = "02:00:00:99:88:01"
        val second = "02:00:00:99:88:02"

        repository.vehiclesFlow.test {
            assertEquals(emptyList<KnownVehicle>(), awaitItem())

            repository.saveVehicle(vehicle(first, name = "First", timestamp = 1_000L))
            assertEquals(listOf("First"), awaitItem().map { it.displayName })

            repository.saveVehicle(vehicle(second, name = "Second", timestamp = 2_000L))
            assertEquals(
                "Most recently connected first, same as getAllVehicles",
                listOf("Second", "First"),
                awaitItem().map { it.displayName }
            )

            repository.removeVehicle(second)
            assertEquals(listOf("First"), awaitItem().map { it.displayName })

            repository.clear()
            assertEquals(emptyList<KnownVehicle>(), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun vehicle(
        mac: String,
        name: String,
        timestamp: Long
    ) = KnownVehicle(
        macAddress = mac,
        displayName = name,
        vehicleInfo = VehicleInformation(
            vehicleType = 0x01,
            displayCapability = byteArrayOf(0x01, 0x02),
            switchType = SwitchType.TYPE_B,
            categoryId = 0x01,
            seriesCode = "RH10",
            destinationId = DestinationId.EU
        ),
        lastConnectedTimestamp = timestamp
    )
}
