package com.opensab.sab.session.vehicle

import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.session.ble.MockGattTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleInfoReaderTest {

    private fun hexToLittleEndianBytes(hex: String): ByteArray {
        val value = hex.toInt(16)
        return byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
    }

    private suspend fun readWithSeriesBytes(
        seriesBytes: ByteArray,
        stored: VehicleProfile? = null
    ): KnownVehicle {
        val transport = MockGattTransport()
        transport.connect()
        transport.setCharacteristicValue(SabGattUuids.CHAR_VEHICLE_TYPE, byteArrayOf(0x01))
        transport.setCharacteristicValue(
            SabGattUuids.CHAR_DISPLAY_CAPABILITY,
            byteArrayOf(0x01, 0x02, 0x03)
        )
        transport.setCharacteristicValue(SabGattUuids.CHAR_SWITCH_TYPE, byteArrayOf(0x02))
        transport.setCharacteristicValue(SabGattUuids.CHAR_CATEGORY_ID, byteArrayOf(0x01))
        transport.setCharacteristicValue(
            SabGattUuids.CHAR_SERIES_CODE,
            seriesBytes
        )
        transport.setCharacteristicValue(SabGattUuids.CHAR_DESTINATION_ID, byteArrayOf(0x02))

        val reader = DefaultVehicleInfoReader()
        return reader.readVehicleIdentity("02:00:00:12:34:56", transport, stored)
            .getOrThrow()
    }

    private suspend fun readWithSeries(
        seriesCode: String,
        stored: VehicleProfile? = null
    ): KnownVehicle = readWithSeriesBytes(hexToLittleEndianBytes(seriesCode), stored)

    @Test
    fun readVehicleIdentitySuccess() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()

        // Populate mock VehicleInformation characteristics
        transport.setCharacteristicValue(SabGattUuids.CHAR_VEHICLE_TYPE, byteArrayOf(0x01))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DISPLAY_CAPABILITY, byteArrayOf(0x01, 0x02, 0x03))
        transport.setCharacteristicValue(SabGattUuids.CHAR_SWITCH_TYPE, byteArrayOf(0x02)) // TYPE_B
        transport.setCharacteristicValue(SabGattUuids.CHAR_CATEGORY_ID, byteArrayOf(0x01))
        transport.setCharacteristicValue(SabGattUuids.CHAR_SERIES_CODE, byteArrayOf(0x36, 0x05))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DESTINATION_ID, byteArrayOf(0x01)) // EU ("ED")
        transport.setCharacteristicValue(SabGattUuids.CHAR_DFU_FIRMWARE_VERSION, byteArrayOf(0x01, 0x04))

        val reader: VehicleInfoReader = DefaultVehicleInfoReader()
        val mac = "02:00:00:12:34:56"
        val result = reader.readVehicleIdentity(mac, transport, stored = VehicleProfile(name = "My CB650R"))

        assertTrue("Expected readVehicleIdentity success", result.isSuccess)
        val vehicle = result.getOrNull()!!

        assertEquals(mac, vehicle.macAddress)
        assertEquals("My CB650R", vehicle.displayName)
        assertEquals(0x01, vehicle.vehicleInfo.vehicleType)
        assertEquals(SwitchType.TYPE_B, vehicle.vehicleInfo.switchType)
        assertEquals("0536", vehicle.vehicleInfo.seriesCode)
        assertEquals(DestinationId.EU, vehicle.vehicleInfo.destinationId)

        assertNotNull(vehicle.firmwareVersion)
        assertEquals("01.04", vehicle.firmwareVersion?.versionString)
    }

    @Test
    fun reidentificationKeepsTheStoredClusterDisplay() = runBlocking {
        val stored = VehicleProfile("Mi CB", BodyStyle.NAKED, ClusterDisplay.SEGMENT)
        val vehicle = readWithSeries("0583", stored)
        assertEquals("Mi CB", vehicle.displayName)
        assertEquals(BodyStyle.NAKED, vehicle.bodyStyle)
        assertEquals(ClusterDisplay.SEGMENT, vehicle.clusterDisplay)
    }

    @Test
    fun aStoredProfileWithoutANameGetsTheDefaultName() = runBlocking {
        assertEquals(
            "Honda 0583",
            readWithSeries("0583", VehicleProfile(clusterDisplay = ClusterDisplay.SEGMENT)).displayName
        )
    }

    @Test
    fun aNeverSeenVehicleGetsTheDefaultProfile() = runBlocking {
        val vehicle = readWithSeries("0536", stored = null)
        assertEquals("Honda 0536", vehicle.displayName)
        assertEquals(BodyStyle.GENERIC, vehicle.bodyStyle)
        assertEquals(ClusterDisplay.COLOR, vehicle.clusterDisplay)
    }

    @Test
    fun anEmptySeriesNamesTheMotorcycleWithoutADanglingSpace() {
        assertEquals("Honda Motorcycle", defaultVehicleName(""))
        assertEquals("Honda Motorcycle", defaultVehicleName("   "))
    }

    @Test
    fun fallsBackToTheSeriesCodeAsTheDisplayName() = runBlocking {
        val vehicle = readWithSeries("9999")

        assertEquals("Honda 9999", vehicle.displayName)
    }

    @Test
    fun parsesASeriesCodeWithHexLetters() = runBlocking {
        val vehicle = readWithSeries("052B")

        assertEquals("052B", vehicle.vehicleInfo.seriesCode)
    }

    @Test
    fun resolvesRawBytes3605To0536() = runBlocking {
        // Field evidence (2026-08-28, CB650R 2024): CHAR_SERIES_CODE returns bytes [0x36, 0x05].
        val vehicle = readWithSeriesBytes(byteArrayOf(0x36, 0x05))

        assertEquals("0536", vehicle.vehicleInfo.seriesCode)
        assertEquals("Honda 0536", vehicle.displayName)
    }
}
