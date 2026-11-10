package com.opensab.app.e2e

import com.opensab.app.session.vehicleInformation
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.BodyType
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.VehicleProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rider-declared [VehicleProfile], not the bundled catalog, decides whether the cluster's first
 * frame after the handover is painted in colour or folded to the panel's two fixed monochrome
 * colours.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class E2eVehicleProfileTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun segmentProfileDegradesTheFirstMenu() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.vehicles.saveVehicle(
            KnownVehicle(
                macAddress = SessionHarness.MAC,
                displayName = "My CB",
                vehicleInfo = vehicleInformation()
            )
        )
        harness.vehicles.saveProfile(
            SessionHarness.MAC,
            VehicleProfile(clusterDisplay = ClusterDisplay.SEGMENT)
        )

        val connectResult = harness.connect()
        assertTrue("Pipeline connects: ${connectResult.exceptionOrNull()}", connectResult.isSuccess)

        harness.handOverScreen()

        val rootFrame = harness.btu.baseFrames().lastOrNull()
        assertNotNull("Granting SAB_MODE projects the root carousel", rootFrame)
        assertEquals("Carousel uses MENU body", BodyType.MENU.value, rootFrame!!.bodyType)
        assertEquals(
            "A segment profile folds the header background to the cluster's fixed black",
            ColorId.PURE_BLACK.id.toByte(),
            rootFrame.payload[2]
        )
    }

    @Test
    fun defaultProfileProjectsInColor() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)

        val connectResult = harness.connect()
        assertTrue("Pipeline connects: ${connectResult.exceptionOrNull()}", connectResult.isSuccess)

        harness.handOverScreen()

        val rootFrame = harness.btu.baseFrames().lastOrNull()
        assertNotNull("Granting SAB_MODE projects the root carousel", rootFrame)
        assertEquals("Carousel uses MENU body", BodyType.MENU.value, rootFrame!!.bodyType)
        assertNotEquals(
            "No stored profile means COLOR by default: the header keeps its normal colour",
            ColorId.PURE_BLACK.id.toByte(),
            rootFrame.payload[2]
        )
    }

    /**
     * The rider answers the cluster-type question before the identity read even happens: only a
     * bare [VehicleProfile] is on record, no [KnownVehicle] yet. `KnownVehicleParticipant` only
     * writes on a [SessionOrigin.SCANNER] session (the harness default is RECEIVER), which is the
     * one first pairing actually uses.
     */
    @Test
    fun firstPairingAnswerSurvivesTheIdentityWriteAndReconnect() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.vehicles.saveProfile(
            SessionHarness.MAC,
            VehicleProfile(clusterDisplay = ClusterDisplay.SEGMENT)
        )

        val connectResult = harness.connect(origin = SessionOrigin.SCANNER)
        assertTrue("Pipeline connects: ${connectResult.exceptionOrNull()}", connectResult.isSuccess)
        runCurrent()

        harness.handOverScreen()

        val firstFrame = harness.btu.baseFrames().lastOrNull()
        assertNotNull("Granting SAB_MODE projects the root carousel", firstFrame)
        assertEquals(
            "The profile answered before pairing already degrades the first frame",
            ColorId.PURE_BLACK.id.toByte(),
            firstFrame!!.payload[2]
        )

        val stored = harness.vehicles.getVehicle(SessionHarness.MAC)
        assertNotNull("KnownVehicleParticipant has to write the identity for a SCANNER session", stored)
        assertEquals(ClusterDisplay.SEGMENT, stored!!.clusterDisplay)
        assertEquals("Honda 0536", stored.displayName)

        harness.disconnect()
        runCurrent()
        val reconnectResult = harness.connect()
        assertTrue("Reconnect succeeds: ${reconnectResult.exceptionOrNull()}", reconnectResult.isSuccess)
        harness.handOverScreen()
        runCurrent()

        val secondFrame = harness.btu.baseFrames().lastOrNull()
        assertNotNull("Reconnecting projects the root carousel again", secondFrame)
        assertEquals(
            "The answer survives the identity write: still degraded after a reconnect",
            ColorId.PURE_BLACK.id.toByte(),
            secondFrame!!.payload[2]
        )
    }
}
