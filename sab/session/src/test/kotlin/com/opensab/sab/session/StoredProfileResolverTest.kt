package com.opensab.sab.session

import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.vehicle.BodyStyle
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.InMemoryVehicleRepository
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.VehicleProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one [StoredVehicleProfileResolver] every caller shares
 * ([VehicleRepository.storedProfileResolver][com.opensab.sab.session.vehicle.VehicleRepository]),
 * proven against [InMemoryVehicleRepository] so every wiring site (`:app`'s `SessionModule`,
 * `SessionHarness`, `SessionIntegrationRig`) can call it instead of copying the three branches.
 */
class StoredProfileResolverTest {

    @Test
    fun prefersTheMergedIdentityOverTheBareProfile() = runTest {
        val repository = InMemoryVehicleRepository()
        repository.saveVehicle(
            KnownVehicle(
                macAddress = "AA:BB:CC:DD:EE:FF",
                displayName = "CB650R",
                vehicleInfo = VehicleInformation(
                    vehicleType = 0x01,
                    displayCapability = byteArrayOf(0x01, 0x02),
                    switchType = SwitchType.TYPE_B,
                    categoryId = 0x01,
                    seriesCode = "0583",
                    destinationId = DestinationId.EU
                )
            )
        )
        repository.saveProfile(
            "AA:BB:CC:DD:EE:FF",
            VehicleProfile(bodyStyle = BodyStyle.NAKED, clusterDisplay = ClusterDisplay.SEGMENT)
        )

        assertEquals(
            VehicleProfile("CB650R", BodyStyle.NAKED, ClusterDisplay.SEGMENT),
            repository.storedProfileResolver().resolveStoredProfile("AA:BB:CC:DD:EE:FF")
        )
    }

    @Test
    fun answersTheBareProfileBeforeTheFirstConnection() = runTest {
        val repository = InMemoryVehicleRepository()
        repository.saveProfile("11:22:33:44:55:66", VehicleProfile(clusterDisplay = ClusterDisplay.SEGMENT))

        assertEquals(
            VehicleProfile(clusterDisplay = ClusterDisplay.SEGMENT),
            repository.storedProfileResolver().resolveStoredProfile("11:22:33:44:55:66")
        )
    }

    @Test
    fun answersNullForAVehicleNeverSeen() = runTest {
        val repository = InMemoryVehicleRepository()

        assertNull(repository.storedProfileResolver().resolveStoredProfile("99:99:99:99:99:99"))
    }
}
