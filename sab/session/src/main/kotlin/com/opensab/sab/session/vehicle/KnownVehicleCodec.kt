package com.opensab.sab.session.vehicle

import com.opensab.logging.Log
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.DeviceInformation
import com.opensab.sab.protocol.vehicle.FirmwareVersion
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Enums are stored by their protocol code and never by constant name, so renaming a Kotlin enum
 * cannot invalidate a profile already written to disk. Unknown codes decode to the fallback
 * constants defined by the protocol.
 */
object KnownVehicleCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(vehicle: KnownVehicle): String = json.encodeToString(vehicle.toDto())

    /** Returns null when the stored record is unreadable: corrupt storage must not crash the app. */
    fun decode(raw: String): KnownVehicle? = runCatchingCancellable {
        json.decodeFromString<KnownVehicleDto>(raw).toDomain()
    }.getOrElse { cause ->
        Log.w(cause, "Discarding unreadable vehicle record")
        null
    }

    @Serializable
    private data class KnownVehicleDto(
        val macAddress: String,
        val displayName: String,
        val vehicleInfo: VehicleInformationDto,
        val deviceInfo: DeviceInformationDto? = null,
        val firmwareVersion: FirmwareVersionDto? = null,
        val lastConnectedTimestamp: Long
    )

    @Serializable
    private data class VehicleInformationDto(
        val vehicleType: Int,
        /** Lowercase hex so the stored file stays readable while debugging. */
        val displayCapability: String,
        val switchType: Int,
        val categoryId: Int,
        val seriesCode: String,
        val destinationId: Int
    )

    @Serializable
    private data class DeviceInformationDto(
        val manufacturerName: String? = null,
        val modelNumber: String? = null,
        val serialNumber: String? = null,
        val hardwareRevision: String? = null,
        val firmwareRevision: String? = null,
        val softwareRevision: String? = null
    )

    @Serializable
    private data class FirmwareVersionDto(val major: String, val minor: String)

    private fun KnownVehicle.toDto() = KnownVehicleDto(
        macAddress = macAddress,
        displayName = displayName,
        vehicleInfo = VehicleInformationDto(
            vehicleType = vehicleInfo.vehicleType,
            displayCapability = vehicleInfo.displayCapability.toHex(),
            switchType = vehicleInfo.switchType.code,
            categoryId = vehicleInfo.categoryId,
            seriesCode = vehicleInfo.seriesCode,
            destinationId = vehicleInfo.destinationId.code
        ),
        deviceInfo = deviceInfo?.let {
            DeviceInformationDto(
                manufacturerName = it.manufacturerName,
                modelNumber = it.modelNumber,
                serialNumber = it.serialNumber,
                hardwareRevision = it.hardwareRevision,
                firmwareRevision = it.firmwareRevision,
                softwareRevision = it.softwareRevision
            )
        },
        firmwareVersion = firmwareVersion?.let { FirmwareVersionDto(it.major, it.minor) },
        lastConnectedTimestamp = lastConnectedTimestamp
    )

    private fun KnownVehicleDto.toDomain() = KnownVehicle(
        macAddress = macAddress,
        displayName = displayName,
        vehicleInfo = VehicleInformation(
            vehicleType = vehicleInfo.vehicleType,
            displayCapability = vehicleInfo.displayCapability.hexToBytes(),
            switchType = SwitchType.fromCode(vehicleInfo.switchType),
            categoryId = vehicleInfo.categoryId,
            seriesCode = vehicleInfo.seriesCode,
            destinationId = DestinationId.fromCode(vehicleInfo.destinationId)
        ),
        deviceInfo = deviceInfo?.let {
            DeviceInformation(
                manufacturerName = it.manufacturerName,
                modelNumber = it.modelNumber,
                serialNumber = it.serialNumber,
                hardwareRevision = it.hardwareRevision,
                firmwareRevision = it.firmwareRevision,
                softwareRevision = it.softwareRevision
            )
        },
        firmwareVersion = firmwareVersion?.let { FirmwareVersion(it.major, it.minor) },
        lastConnectedTimestamp = lastConnectedTimestamp
    )

    private fun ByteArray.toHex(): String =
        joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex payload must have an even length, got $length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte()
        }
    }
}
