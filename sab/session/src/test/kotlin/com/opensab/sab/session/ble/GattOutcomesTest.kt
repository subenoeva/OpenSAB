package com.opensab.sab.session.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GattOutcomesTest {

    @Test
    fun aSuccessfulCallbackCarriesItsValue() =
        assertArrayEquals(byteArrayOf(0x01), GattOutcomes.operation("Read of x", 0, byteArrayOf(0x01)).getOrThrow())

    @Test
    fun anInsufficientAuthenticationReadFailsWithItsStatus() {
        // The first read of the security status on a link that is not bonded yet.
        val failure = GattOutcomes.operation("Read of a65b7152", 5, byteArrayOf()).exceptionOrNull()

        assertTrue(failure is GattOperationFailedException)
        failure as GattOperationFailedException
        assertEquals(5, failure.status)
        assertEquals("Read of a65b7152 failed with GATT status 5 (GATT_INSUFFICIENT_AUTHENTICATION)", failure.message)
    }

    @Test
    fun anAuthFailOnTheRetryFailsWithItsStatus() =
        assertEquals(137, (GattOutcomes.operation("Read", 137, Unit).exceptionOrNull() as GattOperationFailedException).status)

    @Test
    fun anAcceptedSubmitStartsTheOperation() =
        assertTrue(GattOutcomes.submit(GattOutcomes.SUBMIT_ACCEPTED, "Write to x").isSuccess)

    @Test
    fun aBusyStackRefusesTheSubmit() {
        // 201 is ERROR_GATT_WRITE_REQUEST_BUSY: the stack still holds an earlier operation.
        val failure = GattOutcomes.submit(201, "Write to x").exceptionOrNull()

        assertTrue(failure is BleDisconnectedException)
        assertEquals("Write to x could not be started", failure?.message)
    }

    @Test
    fun aLegacyFalseIsARefusal() {
        assertEquals(GattOutcomes.SUBMIT_ACCEPTED, GattOutcomes.legacySubmitCode(true))
        assertTrue(GattOutcomes.submit(GattOutcomes.legacySubmitCode(false), "MTU request").isFailure)
    }

    @Test
    fun aConnectedStateWithSuccessBringsTheLinkUp() =
        assertEquals(LinkEvent.Up, GattOutcomes.link(GattOutcomes.STATE_CONNECTED, 0))

    @Test
    fun aConnectedStateWithAnErrorFailsTheConnect() {
        val event = GattOutcomes.link(GattOutcomes.STATE_CONNECTED, 133)

        assertTrue(event is LinkEvent.ConnectFailed)
        assertEquals("Connection failed with GATT status 133 (GATT_ERROR)", (event as LinkEvent.ConnectFailed).cause.message)
    }

    @Test
    fun aSupervisionTimeoutIsDecodedAsALinkReason() {
        // The BTU loses 12 V on key-off and the phone declares the link dead by supervision timeout.
        val event = GattOutcomes.link(GattOutcomes.STATE_DISCONNECTED, 8)

        assertTrue(event is LinkEvent.Down)
        assertEquals("Peer disconnected with GATT status 8 (GATT_CONN_TIMEOUT)", (event as LinkEvent.Down).cause.message)
    }

    @Test
    fun intermediateLinkStatesAreIgnored() {
        assertEquals(LinkEvent.Ignored, GattOutcomes.link(1, 0)) // STATE_CONNECTING
        assertEquals(LinkEvent.Ignored, GattOutcomes.link(3, 0)) // STATE_DISCONNECTING
    }
}
