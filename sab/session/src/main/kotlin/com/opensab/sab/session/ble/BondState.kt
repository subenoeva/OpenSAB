package com.opensab.sab.session.ble

/**
 * Pairing state of a remote device, as the Android bond state machine reports it.
 *
 * The BTU refuses to serve `CHAR_SECURITY_STATUS` over an unencrypted link, so the SAB handshake
 * cannot start until this reaches [BONDED]. The numeric constants mirror
 * `BluetoothDevice.BOND_NONE` / `BOND_BONDING` / `BOND_BONDED`; they are restated here so the
 * state machine stays testable on the JVM, away from `android.bluetooth`.
 */
enum class BondState {
    /** No link key stored: the next encrypted read will fail with GATT_INSUFFICIENT_AUTHENTICATION. */
    NONE,

    /** Pairing is under way; a terminal event is still to come. */
    BONDING,

    /** A link key is stored, so the stack encrypts the link as soon as it connects. */
    BONDED;

    companion object {
        private const val ANDROID_BOND_BONDING = 11
        private const val ANDROID_BOND_BONDED = 12

        /**
         * Maps a `BluetoothDevice.BOND_*` constant onto the enum.
         *
         * Anything unrecognised degrades to [NONE]: reading an unknown code as "already paired"
         * would send the handshake into the very failure this type exists to prevent.
         */
        fun fromAndroidValue(value: Int): BondState = when (value) {
            ANDROID_BOND_BONDING -> BONDING
            ANDROID_BOND_BONDED -> BONDED
            else -> NONE
        }
    }
}

/**
 * Human-readable names for the reason the stack gives when a bond is lost or refused.
 *
 * The codes arrive in the `android.bluetooth.device.extra.REASON` extra of
 * `ACTION_BOND_STATE_CHANGED`. Names follow AOSP `BluetoothDevice`, so a message can be grepped
 * straight against a `bt_stack` logcat line, exactly like [GattStatus].
 */
object BondFailureReason {

    private const val UNKNOWN = "unknown"
    private const val ABSENT = "no reason reported"

    private val names = mapOf(
        1 to "UNBOND_REASON_AUTH_FAILED",
        2 to "UNBOND_REASON_AUTH_REJECTED",
        3 to "UNBOND_REASON_AUTH_CANCELED",
        4 to "UNBOND_REASON_REMOTE_DEVICE_DOWN",
        5 to "UNBOND_REASON_DISCOVERY_IN_PROGRESS",
        6 to "UNBOND_REASON_AUTH_TIMEOUT",
        7 to "UNBOND_REASON_REPEATED_ATTEMPTS",
        8 to "UNBOND_REASON_REMOTE_AUTH_CANCELED",
        9 to "UNBOND_REASON_REMOVED"
    )

    /** Renders [reason] as `"<code> (<NAME>)"`, keeping the raw number visible either way. */
    fun describe(reason: Int?): String =
        if (reason == null) ABSENT else "$reason (${names[reason] ?: UNKNOWN})"
}
