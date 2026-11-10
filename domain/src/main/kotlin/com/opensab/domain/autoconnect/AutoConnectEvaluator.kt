package com.opensab.domain.autoconnect

object AutoConnectEvaluator {

    private const val HONDA_BTU_PREFIX = "HONDA BTU"
    const val ACTION_ACL_CONNECTED = "android.bluetooth.device.action.ACL_CONNECTED"
    const val ACTION_ACL_DISCONNECTED = "android.bluetooth.device.action.ACL_DISCONNECTED"

    /**
     * [autoConnectEnabled] and [autoDisconnectEnabled] are separate preferences and gate one branch
     * each. A rider who only turned off automatic connecting still expects the session to be torn
     * down when the motorcycle goes out of range.
     */
    fun evaluate(
        action: String?,
        deviceName: String?,
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
        val isHondaBtu = deviceName?.contains(HONDA_BTU_PREFIX, ignoreCase = true) == true
        val isKnown = knownVehicleMacs.any { it.equals(normalizedAddress, ignoreCase = true) }

        return when (action) {
            ACTION_ACL_CONNECTED -> {
                if (autoConnectEnabled && isHondaBtu && isKnown) {
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
