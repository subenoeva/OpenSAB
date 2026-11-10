package com.opensab.sab.session.ble

/**
 * Human-readable names for the numeric status codes the Android GATT stack reports.
 *
 * The same number means different things depending on where it surfaces: an operation callback
 * such as `onCharacteristicRead` reports an Attribute Protocol error (or one of the stack's
 * internal `0x80+` codes), while `onConnectionStateChange` reports the HCI reason the link went
 * down. `8` is INSUFFICIENT_AUTHORIZATION in the first case and a connection timeout in the
 * second, so the caller must say which [Domain] the code came from.
 *
 * Names follow the AOSP stack (`system/stack/include/gatt_api.h` and `hci_error_code.h`) so a
 * message can be grepped straight against a `btsnoop` capture or a `bt_stack` logcat line.
 */
object GattStatus {

    /** Where a status code came from, which decides how it is decoded. */
    enum class Domain {
        /** Result of an ATT operation: read, write, descriptor write, MTU, discovery. */
        ATT,

        /** Reason reported for a link-layer event: connection established or dropped. */
        LINK
    }

    private const val UNKNOWN = "unknown"

    private val attNames = mapOf(
        0 to "GATT_SUCCESS",
        1 to "GATT_INVALID_HANDLE",
        2 to "GATT_READ_NOT_PERMITTED",
        3 to "GATT_WRITE_NOT_PERMITTED",
        4 to "GATT_INVALID_PDU",
        5 to "GATT_INSUFFICIENT_AUTHENTICATION",
        6 to "GATT_REQUEST_NOT_SUPPORTED",
        7 to "GATT_INVALID_OFFSET",
        8 to "GATT_INSUFFICIENT_AUTHORIZATION",
        9 to "GATT_PREPARE_QUEUE_FULL",
        10 to "GATT_ATTRIBUTE_NOT_FOUND",
        11 to "GATT_ATTRIBUTE_NOT_LONG",
        12 to "GATT_INSUFFICIENT_KEY_SIZE",
        13 to "GATT_INVALID_ATTRIBUTE_LENGTH",
        14 to "GATT_UNLIKELY_ERROR",
        15 to "GATT_INSUFFICIENT_ENCRYPTION",
        16 to "GATT_UNSUPPORTED_GROUP_TYPE",
        17 to "GATT_INSUFFICIENT_RESOURCES",
        128 to "GATT_NO_RESOURCES",
        129 to "GATT_INTERNAL_ERROR",
        130 to "GATT_WRONG_STATE",
        131 to "GATT_DB_FULL",
        132 to "GATT_BUSY",
        133 to "GATT_ERROR",
        135 to "GATT_ILLEGAL_PARAMETER",
        137 to "GATT_AUTH_FAIL",
        141 to "GATT_ENCRYPTED_NO_MITM",
        142 to "GATT_NOT_ENCRYPTED",
        143 to "GATT_CONNECTION_CONGESTED",
        257 to "GATT_FAILURE"
    )

    private val linkNames = mapOf(
        0 to "GATT_SUCCESS",
        1 to "GATT_CONN_L2C_FAILURE",
        5 to "HCI_ERR_AUTH_FAILURE",
        6 to "HCI_ERR_KEY_MISSING",
        8 to "GATT_CONN_TIMEOUT",
        19 to "GATT_CONN_TERMINATE_PEER_USER",
        22 to "GATT_CONN_TERMINATE_LOCAL_HOST",
        34 to "GATT_CONN_LMP_TIMEOUT",
        61 to "HCI_ERR_CONN_TERMINATED_MIC_FAILURE",
        62 to "GATT_CONN_FAIL_ESTABLISH",
        133 to "GATT_ERROR",
        256 to "GATT_CONN_CANCEL"
    )

    /** Renders [status] as `"<code> (<NAME>)"`, keeping the raw number visible either way. */
    fun describe(status: Int, domain: Domain): String {
        val names = when (domain) {
            Domain.ATT -> attNames
            Domain.LINK -> linkNames
        }
        return "$status (${names[status] ?: UNKNOWN})"
    }
}
