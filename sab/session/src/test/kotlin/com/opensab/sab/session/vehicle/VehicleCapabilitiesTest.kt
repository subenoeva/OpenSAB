package com.opensab.sab.session.vehicle

import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleCapabilitiesTest {

    private fun vehicle(
        cluster: ClusterDisplay = ClusterDisplay.COLOR,
        capability: ByteArray = byteArrayOf(0x07)
    ) = KnownVehicle(
        macAddress = "02:00:00:12:34:56", displayName = "Honda 0583",
        vehicleInfo = VehicleInformation(0x01, capability, SwitchType.TYPE_B, 0x01, "0583", DestinationId.EU),
        clusterDisplay = cluster
    )

    @Test
    fun permissiveBaselineAllowsColorAndModals() {
        assertTrue(VehicleCapabilities.Permissive.supportsColor)
        assertTrue(VehicleCapabilities.Permissive.supportsModals)
        assertFalse(VehicleCapabilities.Permissive.segmentDisplay)
    }

    @Test
    fun aColorClusterThatDeclaresPopUpsGetsModals() {
        val caps = VehicleCapabilities.from(vehicle())
        assertTrue(caps.supportsColor)
        assertTrue(caps.supportsModals)
    }

    @Test
    fun theBtuStillVetoesModalsOnAColorCluster() {
        val caps = VehicleCapabilities.from(vehicle(capability = byteArrayOf(0x01)))
        assertTrue(caps.supportsColor)
        assertFalse(caps.supportsModals)
    }

    @Test
    fun aSegmentClusterLosesColorAndModalsEvenIfTheBtuDeclaresThem() {
        val caps = VehicleCapabilities.from(vehicle(ClusterDisplay.SEGMENT, byteArrayOf(0x07)))
        assertTrue(caps.segmentDisplay)
        assertFalse(caps.supportsColor)
        assertFalse(caps.supportsModals)
    }

    @Test
    fun anEmptyCapabilityByteDeclaresNothing() =
        assertEquals(emptySet<Int>(), VehicleCapabilities.declaredFrom(byteArrayOf()))

    @Test
    fun `declaredFrom decodes capability bitmask with real hardware vector 0x07`() {
        val declared = VehicleCapabilities.declaredFrom(byteArrayOf(0x07))

        assertEquals(3, declared.size)
        assertTrue(declared.contains(VehicleCapabilities.CAP_INDICATOR))
        assertTrue(declared.contains(VehicleCapabilities.CAP_POP_UP_DISPLAY))
        assertTrue(declared.contains(VehicleCapabilities.CAP_MYSTERY_BOX))
    }

    @Test
    fun `holder resets to permissive on disconnect`() {
        val holder = VehicleCapabilitiesHolder()
        holder.adopt(vehicle(ClusterDisplay.SEGMENT))
        assertFalse(holder.state.value.supportsColor)

        holder.reset()

        assertEquals(VehicleCapabilities.Permissive, holder.state.value)
    }

    @Test
    fun `empty or zero displayCapability does not crash and disables modals`() {
        val emptyDeclared = VehicleCapabilities.declaredFrom(byteArrayOf())
        assertTrue("Empty byte array produces empty capability set", emptyDeclared.isEmpty())

        val zeroDeclared = VehicleCapabilities.declaredFrom(byteArrayOf(0x00))
        assertTrue("Zero byte produces empty capability set", zeroDeclared.isEmpty())

        val caps = VehicleCapabilities.from(vehicle(capability = byteArrayOf(0x00)))
        assertTrue("Color cluster keeps color", caps.supportsColor)
        assertFalse("Absence of CAP_POP_UP_DISPLAY bit disables modals", caps.supportsModals)
    }

    @Test
    fun `declaredFrom safely ignores extra trailing bytes and unknown mask bits`() {
        val multiByte = VehicleCapabilities.declaredFrom(byteArrayOf(0x07, 0x01, 0x02))
        assertEquals("Reads only the first byte mask", 3, multiByte.size)

        val unknownBits = VehicleCapabilities.declaredFrom(byteArrayOf(0xF8.toByte()))
        assertTrue("Unknown bits 3..7 produce empty capability set", unknownBits.isEmpty())
    }
}
