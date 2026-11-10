package com.opensab.sab.session.ble

/** What a GATT link callback means for the connection. */
sealed interface LinkEvent {
    data object Up : LinkEvent
    data class ConnectFailed(val cause: BleDisconnectedException) : LinkEvent
    data class Down(val cause: BleDisconnectedException) : LinkEvent

    /** `STATE_CONNECTING` and `STATE_DISCONNECTING`: nothing to act on. */
    data object Ignored : LinkEvent
}

/**
 * Translates the Android GATT stack's integers into results and exceptions, with no Android on the
 * classpath: the constants are copied by value from `BluetoothGatt`, `BluetoothProfile` and
 * `BluetoothStatusCodes`.
 */
object GattOutcomes {
    const val GATT_SUCCESS = 0
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTED = 2

    /** `BluetoothStatusCodes.SUCCESS`, and what a pre-API 33 `true` maps to. */
    const val SUBMIT_ACCEPTED = 0

    /** A pre-API 33 `false`, which carries no reason. */
    const val SUBMIT_REFUSED_LEGACY = -1

    fun legacySubmitCode(started: Boolean): Int = if (started) SUBMIT_ACCEPTED else SUBMIT_REFUSED_LEGACY

    /** The result of an ATT operation callback. */
    fun <T> operation(name: String, status: Int, value: T): Result<T> =
        if (status == GATT_SUCCESS) Result.success(value) else Result.failure(GattOperationFailedException(name, status))

    /** Whether the stack accepted an operation; a refusal means nothing will call back. */
    fun submit(code: Int, what: String): Result<Unit> =
        if (code == SUBMIT_ACCEPTED) {
            Result.success(Unit)
        } else {
            Result.failure(BleDisconnectedException("$what could not be started"))
        }

    fun link(newState: Int, status: Int): LinkEvent {
        val reason = GattStatus.describe(status, GattStatus.Domain.LINK)
        return when (newState) {
            STATE_CONNECTED -> if (status == GATT_SUCCESS) {
                LinkEvent.Up
            } else {
                LinkEvent.ConnectFailed(BleDisconnectedException("Connection failed with GATT status $reason"))
            }

            STATE_DISCONNECTED -> LinkEvent.Down(BleDisconnectedException("Peer disconnected with GATT status $reason"))
            else -> LinkEvent.Ignored
        }
    }
}
