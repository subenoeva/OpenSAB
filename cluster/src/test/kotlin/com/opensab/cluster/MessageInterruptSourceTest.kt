package com.opensab.cluster

import com.opensab.cluster.message.MessagePageProvider
import com.opensab.cluster.message.MessagePromptPageProvider
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.settings.InboundMessageMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** An unread message claiming the screen according to [InboundMessageMode]. */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageInterruptSourceTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun promptPageFor(source: MockMessageSource, readPage: PageProvider) =
        MessagePromptPageProvider(source.currentMessage, englishClusterText, source, null) { readPage }

    private fun readPageFor(source: MockMessageSource) =
        MessagePageProvider(source.currentMessage, englishClusterText, source)

    @Test
    fun claimsNothingWhileNoMessageIsUnread() = testScope.runTest {
        val source = MockMessageSource()
        val readPage = readPageFor(source)
        val promptPage = promptPageFor(source, readPage)
        val interrupts = MessageInterruptSource(
            claimingScreen = source.claimingScreen,
            mode = MutableStateFlow(InboundMessageMode.PROMPT),
            promptPageFactory = { promptPage },
            readPageFactory = { readPage }
        )

        val emitted = interrupts.interrupting.take(1).toList()

        assertNull("nothing may claim the screen without a message", emitted.single())
    }

    @Test
    fun claimsPromptPageInPromptModeWhenAMessageArrives() = testScope.runTest {
        val source = MockMessageSource()
        val readPage = readPageFor(source)
        val promptPage = promptPageFor(source, readPage)
        val interrupts = MessageInterruptSource(
            claimingScreen = source.claimingScreen,
            mode = MutableStateFlow(InboundMessageMode.PROMPT),
            promptPageFactory = { promptPage },
            readPageFactory = { readPage }
        )
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { interrupts.interrupting.collect { seen += it } }
        runCurrent()

        source.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
        runCurrent()

        assertSame("prompt mode must claim the prompt page", promptPage, seen.last())
        job.cancel()
    }

    @Test
    fun claimsReadPageInReadDirectlyModeWhenAMessageArrives() = testScope.runTest {
        val source = MockMessageSource()
        val readPage = readPageFor(source)
        val promptPage = promptPageFor(source, readPage)
        val interrupts = MessageInterruptSource(
            claimingScreen = source.claimingScreen,
            mode = MutableStateFlow(InboundMessageMode.READ_DIRECTLY),
            promptPageFactory = { promptPage },
            readPageFactory = { readPage }
        )
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { interrupts.interrupting.collect { seen += it } }
        runCurrent()

        source.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
        runCurrent()

        assertSame("read directly mode must claim the read page", readPage, seen.last())
        job.cancel()
    }

    @Test
    fun claimsNothingInIgnoreModeWhenAMessageArrives() = testScope.runTest {
        val source = MockMessageSource()
        val readPage = readPageFor(source)
        val promptPage = promptPageFor(source, readPage)
        val interrupts = MessageInterruptSource(
            claimingScreen = source.claimingScreen,
            mode = MutableStateFlow(InboundMessageMode.IGNORE),
            promptPageFactory = { promptPage },
            readPageFactory = { readPage }
        )
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { interrupts.interrupting.collect { seen += it } }
        runCurrent()

        source.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
        runCurrent()

        assertNull("ignore mode must not claim the screen", seen.last())
        job.cancel()
    }

    @Test
    fun dynamicModeChangeUpdatesClaimedPage() = testScope.runTest {
        val source = MockMessageSource()
        val readPage = readPageFor(source)
        val promptPage = promptPageFor(source, readPage)
        val mode = MutableStateFlow(InboundMessageMode.PROMPT)
        val interrupts = MessageInterruptSource(
            claimingScreen = source.claimingScreen,
            mode = mode,
            promptPageFactory = { promptPage },
            readPageFactory = { readPage }
        )
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { interrupts.interrupting.collect { seen += it } }
        runCurrent()

        source.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
        runCurrent()
        assertSame(promptPage, seen.last())

        mode.value = InboundMessageMode.READ_DIRECTLY
        runCurrent()
        assertSame(readPage, seen.last())

        mode.value = InboundMessageMode.IGNORE
        runCurrent()
        assertNull(seen.last())

        mode.value = InboundMessageMode.PROMPT
        runCurrent()
        assertSame(promptPage, seen.last())

        job.cancel()
    }

    @Test
    fun servesOneInstanceOfEachPageForTheWholeSession() = testScope.runTest {
        val source = MockMessageSource()
        var promptBuilt = 0
        var readBuilt = 0
        val readPage = readPageFor(source)
        val promptPage = promptPageFor(source, readPage)
        val mode = MutableStateFlow(InboundMessageMode.PROMPT)
        val interrupts = MessageInterruptSource(
            claimingScreen = source.claimingScreen,
            mode = mode,
            promptPageFactory = {
                promptBuilt++
                promptPage
            },
            readPageFactory = {
                readBuilt++
                readPage
            }
        )
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { interrupts.interrupting.collect { seen += it } }
        runCurrent()

        source.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
        runCurrent()
        source.yieldScreen()
        runCurrent()
        source.setMessage(MessageInfo(sender = "Elena", body = "I am at the door"))
        runCurrent()

        assertEquals("prompt factory must be called once", 1, promptBuilt)
        assertEquals("read factory not called yet while in prompt mode", 0, readBuilt)

        mode.value = InboundMessageMode.READ_DIRECTLY
        runCurrent()
        assertEquals("read factory called once when mode switched", 1, readBuilt)

        mode.value = InboundMessageMode.PROMPT
        runCurrent()
        assertEquals("prompt factory still called only once", 1, promptBuilt)

        job.cancel()
    }

    @Test
    fun withdrawsTheClaimWhenTheScreenIsYielded() = testScope.runTest {
        val source = MockMessageSource()
        val readPage = readPageFor(source)
        val promptPage = promptPageFor(source, readPage)
        val interrupts = MessageInterruptSource(
            claimingScreen = source.claimingScreen,
            mode = MutableStateFlow(InboundMessageMode.PROMPT),
            promptPageFactory = { promptPage },
            readPageFactory = { readPage }
        )
        val seen = mutableListOf<PageProvider?>()
        val job = backgroundScope.launch { interrupts.interrupting.collect { seen += it } }
        runCurrent()

        source.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
        runCurrent()
        source.yieldScreen()
        runCurrent()

        assertNull("yielding withdraws the claim", seen.last())
        job.cancel()
    }
}
