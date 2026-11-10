package com.opensab.domain.speech

/** Routes voice audio to and from a Bluetooth helmet intercom or headset (HFP voice recognition). */
interface BluetoothVoiceRouter {
    val isHeadsetConnected: Boolean
    val isRoutingActive: Boolean
    suspend fun startVoiceRouting(): Boolean
    suspend fun stopVoiceRouting()
}
