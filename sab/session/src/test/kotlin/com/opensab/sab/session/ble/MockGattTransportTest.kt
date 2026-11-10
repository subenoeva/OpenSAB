package com.opensab.sab.session.ble

import app.cash.turbine.test
import com.opensab.sab.protocol.ble.SabGattUuids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MockGattTransportTest {

    @Test
    fun connectAndRequestMtuShouldTransitionToConnectedWithNegotiatedMtu() = runBlocking {
        val transport = MockGattTransport()
        assertEquals(BleConnectionState.Disconnected, transport.connectionState.value)

        transport.connect()
        val mtuResult = transport.requestMtu(247)
        assertTrue(mtuResult.isSuccess)
        assertEquals(247, mtuResult.getOrNull())
        assertEquals(247, transport.negotiatedMtu)
        assertEquals(BleConnectionState.Connected(247), transport.connectionState.value)
    }

    @Test
    fun readAndWriteCharacteristicsShouldStoreAndReturnBytePayloadsCorrectly() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()

        val samplePayload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val writeResult = transport.writeCharacteristic(
            serviceUuid = SabGattUuids.SERVICE_SECURITY,
            characteristicUuid = SabGattUuids.CHAR_SECURITY_CONTROL_POINT,
            payload = samplePayload
        )

        assertTrue(writeResult.isSuccess)
        assertArrayEquals(samplePayload, writeResult.getOrNull())

        val readResult = transport.readCharacteristic(
            serviceUuid = SabGattUuids.SERVICE_SECURITY,
            characteristicUuid = SabGattUuids.CHAR_SECURITY_CONTROL_POINT
        )
        assertTrue(readResult.isSuccess)
        assertArrayEquals(samplePayload, readResult.getOrNull())
    }

    @Test
    fun observeNotificationsShouldReceiveEmittedByteArraysUsingTurbine() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()

        val expectedByte = byteArrayOf(0x04) // SELECT button

        transport.observeNotifications(
            serviceUuid = SabGattUuids.SERVICE_DATA_COMM,
            characteristicUuid = SabGattUuids.CHAR_FUNCTION_NOTIFICATION
        ).test {
            transport.emitNotification(
                serviceUuid = SabGattUuids.SERVICE_DATA_COMM,
                characteristicUuid = SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
                payload = expectedByte
            )
            val item = awaitItem()
            assertArrayEquals(expectedByte, item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeNotificationsInvokesOnSubscriptionArmedWhenCollected() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()

        var armedCalled = false
        transport.observeNotifications(
            serviceUuid = SabGattUuids.SERVICE_DATA_COMM,
            characteristicUuid = SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
            onSubscriptionArmed = { armedCalled = true }
        ).test {
            assertEquals(true, armedCalled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The double obeys the same rule as the real adapters: cancellation is never a `Result`. */
    @Test
    fun aCancelledWriteThrowsInsteadOfReturningAFailure() = runBlocking {
        val transport = MockGattTransport(
            onCharacteristicWrite = { _, _ -> throw CancellationException("rider closed the link") }
        )
        transport.connect()

        try {
            val result = transport.writeCharacteristic(
                serviceUuid = SabGattUuids.SERVICE_SECURITY,
                characteristicUuid = SabGattUuids.CHAR_SECURITY_CONTROL_POINT,
                payload = byteArrayOf(0x01)
            )
            fail("cancellation came back as $result")
        } catch (expected: CancellationException) {
            assertEquals("rider closed the link", expected.message)
        }
    }
}
