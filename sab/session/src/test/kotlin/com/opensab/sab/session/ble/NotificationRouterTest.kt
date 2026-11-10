package com.opensab.sab.session.ble

import com.opensab.sab.protocol.ble.SabGattUuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationRouterTest {

    // Handle 80 carries the 0x09 mode notification; handle 84 carries the Receipts.
    private val functionNotification = SabGattUuids.CHAR_FUNCTION_NOTIFICATION
    private val controlPoint = SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT

    @Test
    fun deliversEachNotificationOnlyToItsCharacteristic() = runTest(UnconfinedTestDispatcher()) {
        val router = NotificationRouter()
        val onFunction = mutableListOf<List<Byte>>()
        val onControlPoint = mutableListOf<List<Byte>>()
        backgroundScope.launch { router.stream(functionNotification).collect { onFunction += it.toList() } }
        backgroundScope.launch { router.stream(controlPoint).collect { onControlPoint += it.toList() } }

        router.publish(functionNotification, byteArrayOf(0x09, 0x0A))
        router.publish(controlPoint, byteArrayOf(0x01, 0x00))

        assertEquals(listOf(listOf<Byte>(0x09, 0x0A)), onFunction)
        assertEquals(listOf(listOf<Byte>(0x01, 0x00)), onControlPoint)
    }

    @Test
    fun aSubscriberThatFallsBehindLosesTheOldestNotNewest() = runTest(UnconfinedTestDispatcher()) {
        val router = NotificationRouter(capacity = 2)
        val received = mutableListOf<Byte>()
        val gate = CompletableDeferred<Unit>()
        backgroundScope.launch {
            router.stream(controlPoint).collect {
                received += it[0]
                gate.await()
            }
        }

        // The collector holds 1; 2 is dropped once 3 and 4 fill the buffer.
        (1..4).forEach { assertTrue(router.publish(controlPoint, byteArrayOf(it.toByte()))) }
        gate.complete(Unit)

        assertEquals(listOf<Byte>(1, 3, 4), received)
    }
}
