package com.opensab.domain.autoconnect

sealed interface AutoConnectDecision {
    /** An ACL connection from a known Honda BTU. */
    data class Connect(val macAddress: String) : AutoConnectDecision

    /** An ACL disconnection from the motorcycle of the active session. */
    data class Disconnect(val macAddress: String) : AutoConnectDecision

    data object Ignore : AutoConnectDecision
}
