package com.opensab.sab.session.vehicle

import com.opensab.sab.protocol.vehicle.DeviceInformation
import com.opensab.sab.protocol.vehicle.FirmwareVersion
import com.opensab.sab.protocol.vehicle.VehicleInformation

data class KnownVehicle(
    val macAddress: String,
    val displayName: String,
    val vehicleInfo: VehicleInformation,
    val deviceInfo: DeviceInformation? = null,
    val firmwareVersion: FirmwareVersion? = null,
    val lastConnectedTimestamp: Long = System.currentTimeMillis(),

    /** Rider-declared profile fields, applied on top of the stored identity by [withProfile]. */
    val bodyStyle: BodyStyle = BodyStyle.GENERIC,
    val clusterDisplay: ClusterDisplay = ClusterDisplay.COLOR
)
