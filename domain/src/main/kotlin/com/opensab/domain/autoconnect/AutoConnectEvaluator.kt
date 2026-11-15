package com.opensab.domain.autoconnect

object AutoConnectEvaluator {

    const val ACTION_ACL_CONNECTED = "android.bluetooth.device.action.ACL_CONNECTED"
    const val ACTION_ACL_DISCONNECTED = "android.bluetooth.device.action.ACL_DISCONNECTED"

    /**
     * [autoConnectEnabled] and [autoDisconnectEnabled] are separate preferences and gate one branch
     * each. A rider who only turned off automatic connecting still expects the session to be torn
     * down when the motorcycle goes out of range.
     */
    fun evaluate(
        action: String?,
        deviceAddress: String?,
        knownVehicleMacs: Set<String>,
        autoConnectEnabled: Boolean,
        activeSessionMac: String? = null,
        autoDisconnectEnabled: Boolean = true
    ): AutoConnectDecision {
        if (deviceAddress == null) {
            return AutoConnectDecision.Ignore
        }

        val normalizedAddress = deviceAddress.uppercase()
        // The address alone: the name is missing without BLUETOOTH_CONNECT or before it is cached,
        // and a motorcycle on file was identified by its address when it was paired.
        val isKnown = knownVehicleMacs.any { it.equals(normalizedAddress, ignoreCase = true) }

        return when (action) {
            ACTION_ACL_CONNECTED -> {
                if (autoConnectEnabled && isKnown) {
                    AutoConnectDecision.Connect(normalizedAddress)
                } else {
                    AutoConnectDecision.Ignore
                }
            }
            ACTION_ACL_DISCONNECTED -> {
                val matchesActiveSession = activeSessionMac != null &&
                    normalizedAddress.equals(activeSessionMac, ignoreCase = true)
                if (autoDisconnectEnabled && matchesActiveSession) {
                    AutoConnectDecision.Disconnect(normalizedAddress)
                } else {
                    AutoConnectDecision.Ignore
                }
            }
            else -> AutoConnectDecision.Ignore
        }
    }
}
