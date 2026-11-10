package com.opensab.sab.session.vehicle

import com.opensab.logging.Log
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.FirmwareVersionParser
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInfoParsers
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.coroutines.runCatchingCancellable

interface VehicleInfoReader {
    suspend fun readVehicleIdentity(
        macAddress: String,
        transport: GattTransport,
        stored: VehicleProfile? = null
    ): Result<KnownVehicle>
}

/** The honest fallback for a series the rider never named: `Honda <series>`, or the plain
 * marque when the BTU itself reports an empty series code. */
fun defaultVehicleName(seriesCode: String): String =
    seriesCode.trim().takeIf { it.isNotEmpty() }?.let { "Honda $it" } ?: "Honda Motorcycle"

class DefaultVehicleInfoReader : VehicleInfoReader {

    override suspend fun readVehicleIdentity(
        macAddress: String,
        transport: GattTransport,
        stored: VehicleProfile?
    ): Result<KnownVehicle> = runCatchingCancellable {
        Log.d("Reading vehicle information from GATT for $macAddress")

        val typeBytes = transport.readCharacteristic(
            SabGattUuids.SERVICE_VEHICLE_INFO,
            SabGattUuids.CHAR_VEHICLE_TYPE
        ).getOrThrow()
        val vehicleType = VehicleInfoParsers.parseSingleByte(typeBytes)

        val displayCapBytes = transport.readCharacteristic(
            SabGattUuids.SERVICE_VEHICLE_INFO,
            SabGattUuids.CHAR_DISPLAY_CAPABILITY
        ).getOrThrow()

        val switchTypeBytes = transport.readCharacteristic(
            SabGattUuids.SERVICE_VEHICLE_INFO,
            SabGattUuids.CHAR_SWITCH_TYPE
        ).getOrThrow()
        val switchType = SwitchType.fromCode(VehicleInfoParsers.parseSingleByte(switchTypeBytes))

        val categoryIdBytes = transport.readCharacteristic(
            SabGattUuids.SERVICE_VEHICLE_INFO,
            SabGattUuids.CHAR_CATEGORY_ID
        ).getOrThrow()
        val categoryId = VehicleInfoParsers.parseSingleByte(categoryIdBytes)

        val seriesCodeBytes = transport.readCharacteristic(
            SabGattUuids.SERVICE_VEHICLE_INFO,
            SabGattUuids.CHAR_SERIES_CODE
        ).getOrThrow()
        val seriesCode = VehicleInfoParsers.parseSeriesCode(seriesCodeBytes)

        val destIdBytes = transport.readCharacteristic(
            SabGattUuids.SERVICE_VEHICLE_INFO,
            SabGattUuids.CHAR_DESTINATION_ID
        ).getOrThrow()
        val destinationId = DestinationId.fromCode(VehicleInfoParsers.parseSingleByte(destIdBytes))

        val vehicleInfo = VehicleInformation(
            vehicleType = vehicleType,
            displayCapability = displayCapBytes,
            switchType = switchType,
            categoryId = categoryId,
            seriesCode = seriesCode,
            destinationId = destinationId
        )

        // Optional: a failed firmware version read does not fail the identity.
        val fwBytes = transport.readCharacteristic(
            SabGattUuids.SERVICE_DFU,
            SabGattUuids.CHAR_DFU_FIRMWARE_VERSION
        ).getOrNull()
        val firmwareVersion = FirmwareVersionParser.parse(fwBytes)

        val displayName = stored?.name ?: defaultVehicleName(seriesCode)
        val bodyStyle = stored?.bodyStyle ?: BodyStyle.GENERIC
        val clusterDisplay = stored?.clusterDisplay ?: ClusterDisplay.COLOR
        Log.i("Identity: series %s, profile %s/%s", seriesCode, bodyStyle, clusterDisplay)

        KnownVehicle(
            macAddress = macAddress,
            displayName = displayName,
            vehicleInfo = vehicleInfo,
            deviceInfo = null,
            firmwareVersion = firmwareVersion,
            lastConnectedTimestamp = System.currentTimeMillis(),
            bodyStyle = bodyStyle,
            clusterDisplay = clusterDisplay
        )
    }
}
