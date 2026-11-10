package com.opensab.domain.message

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSenderTest {

    @Test
    fun mockSmsSenderDefaultsToEmptyAndSuccess() = runTest {
        val sender = MockSmsSender()

        assertEquals(0, sender.sendCount)
        assertTrue(sender.sentMessages.isEmpty())
        assertTrue(sender.nextResult)
    }

    @Test
    fun mockSmsSenderRecordsSentRecipientAndText() = runTest {
        val sender = MockSmsSender()

        val result = sender.send("+123456789", "Hello from OpenSAB")

        assertTrue(result)
        assertEquals(1, sender.sendCount)
        assertEquals(listOf("+123456789" to "Hello from OpenSAB"), sender.sentMessages)
    }

    @Test
    fun mockSmsSenderReturnsConfiguredFailure() = runTest {
        val sender = MockSmsSender(nextResult = false)

        val result = sender.send("+987654321", "Test failure")

        assertFalse(result)
        assertEquals(1, sender.sendCount)
        assertEquals("+987654321" to "Test failure", sender.sentMessages.first())
    }

    @Test
    fun mockSmsSenderAccumulatesMultipleMessagesInOrder() = runTest {
        val sender = MockSmsSender()

        sender.send("111", "First message")
        sender.send("222", "Second message")
        sender.send("333", "Third message")

        assertEquals(3, sender.sendCount)
        assertEquals(
            listOf(
                "111" to "First message",
                "222" to "Second message",
                "333" to "Third message"
            ),
            sender.sentMessages
        )
    }

    @Test
    fun mockSmsSenderSatisfiesSmsSenderInterfaceContract() = runTest {
        val sender: SmsSender = MockSmsSender()
        val outcome = sender.send("5551234", "Interface contract verification")
        assertTrue(outcome)
    }
}
