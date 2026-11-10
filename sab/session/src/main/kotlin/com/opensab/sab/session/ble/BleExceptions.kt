package com.opensab.sab.session.ble

import com.opensab.sab.protocol.ble.SabGattUuids
import java.util.UUID
import kotlin.time.Duration

sealed class BleTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A GATT operation never produced a callback within the watchdog window.
 *
 * [operation] names what was in flight. The Android stack can silently swallow a callback — it
 * retries a read that came back INSUFFICIENT_AUTHENTICATION with a higher `authReq` and never
 * reports the first failure — so a timeout is not proof that the peer stayed quiet.
 */
internal class BleTimeoutException(
    val operation: String = "GATT operation",
    val timeout: Duration = SabGattUuids.OPERATION_TIMEOUT
) : BleTransportException("$operation timed out after ${timeout.inWholeMilliseconds}ms")

/**
 * Pairing never reached a terminal state inside [DefaultDeviceBonder.BOND_TIMEOUT].
 *
 * Its own type and not a [BleTimeoutException]: the queue's watchdog raises that one for any GATT
 * operation, on a window of a couple of seconds, and the rider reads a different sentence for each.
 * A bond that reports nothing at all is a pairing prompt nobody accepted in time; a bond that
 * reports a reason is a stale link key, which is [BondFailedException].
 */
class BondTimedOutException(
    val macAddress: String,
    val timeout: Duration
) : BleTransportException("Pairing with $macAddress did not complete within ${timeout.inWholeMilliseconds}ms")

class BleDisconnectedException(
    message: String = "BLE connection was terminated unexpectedly",
    cause: Throwable? = null
) : BleTransportException(message, cause)

class GattCharacteristicNotFoundException(
    val characteristicUuid: UUID,
    message: String = "GATT characteristic not found: $characteristicUuid"
) : BleTransportException(message)

/**
 * A GATT operation completed with a non-success status.
 *
 * [operation] names what failed (a read, a write, the MTU request…) and [status] keeps the raw
 * code so callers can branch on it; the message decodes it against [domain].
 */
class GattOperationFailedException(
    val operation: String,
    val status: Int,
    val domain: GattStatus.Domain = GattStatus.Domain.ATT
) : BleTransportException(
    "$operation failed with GATT status ${GattStatus.describe(status, domain)}"
)

/**
 * Pairing failed, so the encrypted link the SAB handshake needs never came up.
 *
 * [reason] keeps the raw `UNBOND_REASON_*` code the stack reported, or `null` when it never got
 * as far as reporting one; the default message decodes it through [BondFailureReason].
 */
class BondFailedException(
    val macAddress: String,
    val reason: Int?,
    message: String = "Pairing with $macAddress failed: ${BondFailureReason.describe(reason)}"
) : BleTransportException(message)
