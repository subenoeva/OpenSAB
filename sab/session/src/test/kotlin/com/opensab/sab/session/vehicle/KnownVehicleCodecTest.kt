package com.opensab.sab.session.vehicle

import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.DeviceInformation
import com.opensab.sab.protocol.vehicle.FirmwareVersion
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownVehicleCodecTest {

    private fun sampleVehicle(
        mac: String = "02:00:00:99:88:77",
        displayCapability: ByteArray = byteArrayOf(0x01, 0x02),
        deviceInfo: DeviceInformation? = DeviceInformation(
            manufacturerName = "Honda",
            modelNumber = "BTU-01",
            serialNumber = "SN-0001",
            hardwareRevision = "HW1",
            firmwareRevision = "FW2",
            softwareRevision = "SW3"
        ),
        firmwareVersion: FirmwareVersion? = FirmwareVersion("01", "07"),
        timestamp: Long = 1_724_000_000_000L
    ) = KnownVehicle(
        macAddress = mac,
        displayName = "Honda Forza 750",
        vehicleInfo = VehicleInformation(
            vehicleType = 0x01,
            displayCapability = displayCapability,
            switchType = SwitchType.TYPE_B,
            categoryId = 0x03,
            seriesCode = "RH10",
            destinationId = DestinationId.EU
        ),
        deviceInfo = deviceInfo,
        firmwareVersion = firmwareVersion,
        lastConnectedTimestamp = timestamp
    )

    @Test
    fun roundTripsAllFieldsIncludingOptionalMetadata() {
        val original = sampleVehicle()

        val decoded = KnownVehicleCodec.decode(KnownVehicleCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun roundTripsWithNullOptionalMetadata() {
        val original = sampleVehicle(deviceInfo = null, firmwareVersion = null)

        val decoded = KnownVehicleCodec.decode(KnownVehicleCodec.encode(original))

        assertNotNull(decoded)
        assertNull(decoded?.deviceInfo)
        assertNull(decoded?.firmwareVersion)
        assertEquals(original, decoded)
    }

    @Test
    fun preservesDisplayCapabilityBytes() {
        val capability = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val original = sampleVehicle(displayCapability = capability)

        val decoded = KnownVehicleCodec.decode(KnownVehicleCodec.encode(original))

        assertArrayEquals(capability, decoded?.vehicleInfo?.displayCapability)
    }

    @Test
    fun encodesEnumsByProtocolCodeNotByName() {
        val json = KnownVehicleCodec.encode(sampleVehicle())

        // Renaming a Kotlin enum constant must not invalidate what is already on disk.
        assertTrue("Expected the switch type protocol code in $json", json.contains("\"switchType\":2"))
        assertTrue("Expected the destination protocol code in $json", json.contains("\"destinationId\":1"))
    }

    @Test
    fun decodesUnknownEnumCodesToFallbackConstants() {
        val json = KnownVehicleCodec.encode(sampleVehicle())
            .replace("\"switchType\":2", "\"switchType\":99")
            .replace("\"destinationId\":1", "\"destinationId\":99")

        val decoded = KnownVehicleCodec.decode(json)

        assertEquals(SwitchType.UNKNOWN, decoded?.vehicleInfo?.switchType)
        assertEquals(DestinationId.UNKNOWN, decoded?.vehicleInfo?.destinationId)
    }

    @Test
    fun returnsNullOnMalformedJson() {
        assertNull(KnownVehicleCodec.decode("not json at all"))
        assertNull(KnownVehicleCodec.decode(""))
        assertNull(KnownVehicleCodec.decode("{\"macAddress\":\"02:00:00:99:88:77\"}"))
    }
}
