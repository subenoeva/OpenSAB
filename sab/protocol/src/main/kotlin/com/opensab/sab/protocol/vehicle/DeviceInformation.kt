package com.opensab.sab.protocol.vehicle

/**
 * Metadata read from standard BLE Device Information Service (DIS 0x180A).
 */
data class DeviceInformation(
    val manufacturerName: String? = null,
    val modelNumber: String? = null,
    val serialNumber: String? = null,
    val hardwareRevision: String? = null,
    val firmwareRevision: String? = null,
    val softwareRevision: String? = null
)
