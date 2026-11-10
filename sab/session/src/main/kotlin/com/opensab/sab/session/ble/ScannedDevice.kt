package com.opensab.sab.session.ble

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isSabCompatible: Boolean = true
)
