package com.opensab.sab.session.ble

import com.opensab.sab.protocol.ble.SabGattUuids
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class BleExceptionsTest {

    @Test
    fun operationFailureNamesTheOperationAndDecodesTheStatus() {
        val exception = GattOperationFailedException(
            operation = "read of ${SabGattUuids.CHAR_SECURITY_STATUS}",
            status = 5
        )

        assertEquals(
            "read of a65b7152-589e-4933-a426-8f729bfad439 failed with GATT status " +
                "5 (GATT_INSUFFICIENT_AUTHENTICATION)",
            exception.message
        )
    }

    @Test
    fun operationFailureKeepsTheRawStatusForCallers() {
        assertEquals(137, GattOperationFailedException("read of security status", 137).status)
    }

    @Test
    fun operationFailureDecodesLinkStatusesAgainstTheLinkDomain() {
        val exception = GattOperationFailedException(
            operation = "connection to 02:00:00:00:00:AB",
            status = 19,
            domain = GattStatus.Domain.LINK
        )

        assertEquals(
            "connection to 02:00:00:00:00:AB failed with GATT status " +
                "19 (GATT_CONN_TERMINATE_PEER_USER)",
            exception.message
        )
    }

    @Test
    fun timeoutNamesTheOperationThatActuallyTimedOut() {
        val exception = BleTimeoutException(
            operation = "read of ${SabGattUuids.CHAR_SECURITY_STATUS}",
            timeout = 2.seconds
        )

        assertEquals(
            "read of a65b7152-589e-4933-a426-8f729bfad439 timed out after 2000ms",
            exception.message
        )
    }
}
