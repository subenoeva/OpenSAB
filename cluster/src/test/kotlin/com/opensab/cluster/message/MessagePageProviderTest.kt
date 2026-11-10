package com.opensab.cluster.message

import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.music.VolumeLevel
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MessagePageProviderTest {

    private class TestVolumeSource : VolumeSource {
        val state = MutableStateFlow(VolumeLevel())
        override val level = state

        var raiseCalls = 0
        var lowerCalls = 0
        var muteCalls = 0

        override fun start() {}
        override fun stop() {}
        override fun raise() { raiseCalls++ }
        override fun lower() { lowerCalls++ }
        override fun toggleMute() { muteCalls++ }
    }

    @Test
    fun `renders active message with sender and body in blue live screen`() {
        val messageSource = MockMessageSource()
        val volumeSource = TestVolumeSource()
        val provider = MessagePageProvider(messageSource.currentMessage, englishClusterText, messageSource, volumeSource = volumeSource)

        messageSource.setMessage(
            MessageInfo(
                sender = "Elena",
                body = "See you in 10 minutes!",
                appName = "WhatsApp"
            )
        )

        val frame = provider.render(requestId = 401) as ProjectionFrame.LiveScreen
        assertEquals(401, frame.requestId)
        assertEquals("Messages", frame.headerText)
        assertEquals(ColorId.BLUE, frame.headerBgColorId)
        assertEquals("Elena", frame.contentTextLine1)
        assertEquals("See you in 10 minutes!", frame.contentTextLine2)
        // The body line takes the place of the icon: a live screen carries one or the other.
        assertEquals(IconId.IC_NONE, frame.contentIconId)
        assertEquals(IconId.LIVE_CANCEL_TWO_BUTTONS, frame.leftActionContentIconId)
    }

    /**
     * The cluster may not offer a button the handlebar does nothing with.
     *
     * The **branch for a notification that publishes no `RemoteInput`**, which is a grouped
     * summary, a muted thread, or an app outside the ones that offer a reply at all: an arrow saying
     * "here is a way through" over a press that goes nowhere is a lie.
     */
    @Test
    fun `paints no reply button when the notification carries no reply`() {
        val messageSource = MockMessageSource()
        val provider = MessagePageProvider(messageSource.currentMessage, englishClusterText, messageSource)

        messageSource.setMessage(MessageInfo(sender = "Elena", body = "See you in 10 minutes!"))

        val frame = provider.render(requestId = 403) as ProjectionFrame.LiveScreen
        assertEquals(IconId.IC_NONE, frame.rightActionIconId)
        assertNull(frame.rightActionContentIconId)
        assertNull(frame.rightActionContentIconTintColorId)
        assertNull(frame.rightActionBgColorId)
    }

    @Test
    fun `renders standby screen when no message is active`() {
        val messageSource = MockMessageSource()
        val provider = MessagePageProvider(messageSource.currentMessage, englishClusterText, messageSource)

        val frame = provider.render(requestId = 402) as ProjectionFrame.LiveScreen
        assertEquals(402, frame.requestId)
        assertEquals("Messages", frame.headerText)
        assertEquals(ColorId.BLUE, frame.headerBgColorId)
        assertEquals(IconId.MESSAGE, frame.contentIconId)
        assertEquals("No Messages", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun `back button pops back to root menu and yields the screen`() {
        val messageSource = MockMessageSource()
        val provider = MessagePageProvider(messageSource.currentMessage, englishClusterText, messageSource)

        messageSource.setMessage(MessageInfo(sender = "Bob", body = "Hello"))

        val action = provider.onInput(FunctionId.BACK)
        assertEquals(NavigationAction.Pop, action)
        assertEquals(1, messageSource.yieldCalls)
        assertNotNull(
            "BACK yields the screen, it does not throw the message away",
            messageSource.currentMessage.value
        )
    }

    @Test
    fun `menu button resets back to root menu and yields the screen`() {
        val messageSource = MockMessageSource()
        val provider = MessagePageProvider(messageSource.currentMessage, englishClusterText, messageSource)

        messageSource.setMessage(MessageInfo(sender = "Bob", body = "Hello"))

        val action = provider.onInput(FunctionId.MENU)
        assertEquals(NavigationAction.Reset, action)
        assertEquals(1, messageSource.yieldCalls)
        assertNotNull(
            "MENU resets with the MESSAGE indicator still on, so the message survives",
            messageSource.currentMessage.value
        )
    }

    @Test
    fun `select button returns none action`() {
        val messageSource = MockMessageSource()
        val provider = MessagePageProvider(messageSource.currentMessage, englishClusterText, messageSource)

        val action = provider.onInput(FunctionId.SELECT)
        assertEquals(NavigationAction.None, action)
    }

    @Test
    fun `previous and volume up buttons raise volume`() {
        val messageSource = MockMessageSource()
        val volumeSource = TestVolumeSource()
        val provider = MessagePageProvider(messageSource.currentMessage, englishClusterText, messageSource, volumeSource = volumeSource)

        provider.onInput(FunctionId.PREVIOUS)
        assertEquals(1, volumeSource.raiseCalls)

        provider.onInput(FunctionId.VOLUME_UP)
        assertEquals(2, volumeSource.raiseCalls)
    }

    @Test
    fun `next and volume down buttons lower volume`() {
        val messageSource = MockMessageSource()
        val volumeSource = TestVolumeSource()
        val provider = MessagePageProvider(messageSource.currentMessage, englishClusterText, messageSource, volumeSource = volumeSource)

        provider.onInput(FunctionId.NEXT)
        assertEquals(1, volumeSource.lowerCalls)

        provider.onInput(FunctionId.VOLUME_DOWN)
        assertEquals(2, volumeSource.lowerCalls)
    }

    @Test
    fun `volume mute button toggles mute`() {
        val messageSource = MockMessageSource()
        val volumeSource = TestVolumeSource()
        val provider = MessagePageProvider(messageSource.currentMessage, englishClusterText, messageSource, volumeSource = volumeSource)

        provider.onInput(FunctionId.VOLUME_MUTE)
        assertEquals(1, volumeSource.muteCalls)
    }

    @Test
    fun `discards state on resume`() {
        val messageSource = MockMessageSource()
        val provider = MessagePageProvider(messageSource.currentMessage, englishClusterText, messageSource)

        assertEquals(ResumeBehavior.DISCARD, provider.onResumeBehavior)
        assertEquals("MessagePage", provider.name)
    }

    // -------------------------------------------------------------------------------------------
    // Mirror
    // -------------------------------------------------------------------------------------------

    /**
     * The shared volume block of the content pages, plus the two differences of this one: `BACK`
     * carries [MirrorActionToken.DISMISS_MESSAGE] rather than `GO_BACK`, because the branch calls
     * `yieldScreen()` before popping, and `SELECT` carries [MirrorActionToken.REPLY_MESSAGE] —
     * declared always and enabled only when the live notification can be answered.
     */
    private fun expectedActions(canReply: Boolean = false) = listOf(
        MirrorAction(
            token = MirrorActionToken.DISMISS_MESSAGE,
            functionId = FunctionId.BACK,
            slot = MirrorSlot.LEFT
        ),
        MirrorAction(
            token = MirrorActionToken.REPLY_MESSAGE,
            functionId = FunctionId.SELECT,
            slot = MirrorSlot.PRIMARY,
            enabled = canReply
        ),
        MirrorAction(
            token = MirrorActionToken.VOLUME_UP,
            functionId = FunctionId.PREVIOUS,
            slot = MirrorSlot.UP,
            aliases = setOf(FunctionId.VOLUME_UP)
        ),
        MirrorAction(
            token = MirrorActionToken.VOLUME_DOWN,
            functionId = FunctionId.NEXT,
            slot = MirrorSlot.DOWN,
            aliases = setOf(FunctionId.VOLUME_DOWN)
        ),
        MirrorAction(
            token = MirrorActionToken.VOLUME_MUTE,
            functionId = FunctionId.VOLUME_MUTE,
            slot = MirrorSlot.RIGHT
        ),
        MirrorAction(
            token = MirrorActionToken.OPEN_ROOT_MENU,
            functionId = FunctionId.MENU,
            slot = MirrorSlot.HEADER
        )
    )

    @Test
    fun `mirror declares dismiss, reply, the volume block and the escape to the root menu`() {
        val messageSource = MockMessageSource()
        val volumeSource = TestVolumeSource()
        val provider = MessagePageProvider(
            currentMessage = messageSource.currentMessage,
            copy = englishClusterText,
            messageSource = messageSource,
            canReply = messageSource.canReply,
            volumeSource = volumeSource
        )

        messageSource.setMessage(MessageInfo(sender = "Elena", body = "See you in 10 minutes!"))
        messageSource.setCanReply(true)

        val page = provider.mirror()
        assertEquals("MessagePage", page.pageName)
        assertEquals(expectedActions(canReply = true), page.actions)
        assertTrue(page.actions.all { it.enabled })
    }

    /**
     * Standby declares the very same zones: `BACK` still clears and pops, and the volume source is
     * still the page's own, so nothing goes dark just because the inbox is empty.
     */
    /**
     * Standby declares the very same zones, reply included and disabled: nothing goes dark just
     * because the inbox is empty, and no zone moves under the rider's glance between one message
     * and the next.
     */
    @Test
    fun `mirror declares the same actions in standby`() {
        val messageSource = MockMessageSource()
        val volumeSource = TestVolumeSource()
        val provider = MessagePageProvider(messageSource.currentMessage, englishClusterText, messageSource, volumeSource = volumeSource)

        assertEquals(expectedActions(canReply = false), provider.mirror().actions)
    }

    // -----------------------------------------------------------------------------------------
    // The voice
    // -----------------------------------------------------------------------------------------

    private fun readingPage(source: MockMessageSource) =
        MessagePageProvider(source.currentMessage, englishClusterText, source)

    @Test
    fun `reads the body out loud when the message is opened`() = runTest {
        val source = MockMessageSource()
        val provider = readingPage(source)
        source.setMessage(MessageInfo(sender = "Elena", body = "Llego en 10 minutos"))

        val script = provider.speech.first()

        assertEquals(listOf(SpeechToken.MESSAGE_READ), script.map { it.token })
        assertEquals(listOf(SpeechArg.Text("Llego en 10 minutos")), script.single().args)
    }

    /** The whole point of the row: a Chinese body read with a Spanish voice is noise. */
    @Test
    fun `reads a chinese body with the chinese voice`() = runTest {
        val source = MockMessageSource()
        val provider = readingPage(source)
        source.setMessage(MessageInfo(sender = "Wei", body = "你好，你在哪里"))

        assertEquals(Locale.forLanguageTag("zh"), provider.speech.first().single().locale)
    }

    @Test
    fun `reads a latin body with the rider voice`() = runTest {
        val source = MockMessageSource()
        val provider = readingPage(source)
        source.setMessage(MessageInfo(sender = "Elena", body = "Llego en 10 minutos"))

        assertNull(provider.speech.first().single().locale)
    }

    @Test
    fun `reads the next message of the thread`() = runTest {
        val source = MockMessageSource()
        val provider = readingPage(source)
        source.setMessage(MessageInfo(sender = "Elena", body = "Llego en 10", timestamp = 1L))
        provider.speech.first()

        source.setMessage(MessageInfo(sender = "Elena", body = "Ya estoy aqui", timestamp = 2L))

        assertEquals(
            listOf(SpeechArg.Text("Ya estoy aqui")),
            provider.speech.first().single().args
        )
    }

    /**
     * A call covering the page and going away is a remount, and it must not start the body again
     * from the top.
     */
    @Test
    fun `does not read the same message twice on a remount`() = runTest {
        val source = MockMessageSource()
        val provider = readingPage(source)
        source.setMessage(MessageInfo(sender = "Elena", body = "Llego en 10 minutos"))
        provider.speech.first()

        assertNull(withTimeoutOrNull(1.seconds) { provider.speech.first() })
    }

    @Test
    fun `says nothing on standby`() = runTest {
        val provider = readingPage(MockMessageSource())

        assertNull(withTimeoutOrNull(1.seconds) { provider.speech.first() })
    }

    /** An empty body is nothing to read, and an empty utterance is worse than silence. */
    @Test
    fun `says nothing when the body is empty`() = runTest {
        val source = MockMessageSource()
        val provider = readingPage(source)
        source.setMessage(MessageInfo(sender = "Elena", body = ""))

        assertNull(withTimeoutOrNull(1.seconds) { provider.speech.first() })
    }

    /**
     * `SELECT` stays silent and stays reserved for replying.
     */
    @Test
    fun `keeps select silent and reserved for replying`() = runTest {
        val source = MockMessageSource()
        val provider = readingPage(source)
        source.setMessage(MessageInfo(sender = "Elena", body = "Llego en 10 minutos"))
        provider.speech.first()

        assertEquals(NavigationAction.None, provider.onInput(FunctionId.SELECT))
        assertNull(withTimeoutOrNull(1.seconds) { provider.speech.first() })
    }

    // -----------------------------------------------------------------------------------------
    // Replying
    // -----------------------------------------------------------------------------------------

    private fun replyingPage(
        source: MockMessageSource,
        volume: TestVolumeSource? = null,
        replyPage: PageProvider = StubPageProvider("reply", ProjectionFrame.RequestSabMode(1))
    ) = MessagePageProvider(
        currentMessage = source.currentMessage,
        copy = englishClusterText,
        messageSource = source,
        canReply = source.canReply,
        volumeSource = volume,
        replyPageFactory = { replyPage }
    )

    @Test
    fun `select opens the reply page when the app can reply`() {
        val source = MockMessageSource()
        val replyPage = StubPageProvider("reply", ProjectionFrame.RequestSabMode(1))
        val provider = replyingPage(source, replyPage = replyPage)
        source.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
        source.setCanReply(true)

        val action = provider.onInput(FunctionId.SELECT)

        assertEquals(NavigationAction.Next(replyPage), action)
        assertEquals("opening the reply does not give the screen back", 0, source.yieldCalls)
    }

    /** One page per session, as with the reading: a second one would be a second identity. */
    @Test
    fun `select hands over the same reply page every time`() {
        val source = MockMessageSource()
        val provider = replyingPage(source)
        source.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
        source.setCanReply(true)

        val first = provider.onInput(FunctionId.SELECT) as NavigationAction.Next
        val second = provider.onInput(FunctionId.SELECT) as NavigationAction.Next

        assertSame(first.page, second.page)
    }

    @Test
    fun `select stays silent when the app publishes no reply`() {
        val source = MockMessageSource()
        val provider = replyingPage(source)
        source.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))

        assertEquals(NavigationAction.None, provider.onInput(FunctionId.SELECT))
    }

    @Test
    fun `select stays silent on standby even when a reply was once possible`() {
        val source = MockMessageSource()
        val provider = replyingPage(source)
        source.setCanReply(true)

        assertEquals(
            "no message means nothing to reply to, whatever the last notification offered",
            NavigationAction.None,
            provider.onInput(FunctionId.SELECT)
        )
    }

    @Test
    fun `paints the reply button when the app can reply`() {
        val source = MockMessageSource()
        val provider = replyingPage(source)
        source.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
        source.setCanReply(true)

        val frame = provider.render(requestId = 404) as ProjectionFrame.LiveScreen

        assertEquals(IconId.LIVE_ARROW_RIGHT, frame.rightActionIconId)
        assertEquals(IconId.LIVE_REPLY, frame.rightActionContentIconId)
        assertEquals(ColorId.POSITIVE, frame.rightActionBgColorId)
    }

    /**
     * Declared and disabled, not absent: a zone that vanishes shifts the rest of the card under a
     * glance, and the mirror does not get to hide a function the rider has on the handlebar. It is
     * the shape `READ_MESSAGE` already takes on the prompt page in standby.
     */
    @Test
    fun `mirror declares the reply disabled when the app cannot reply`() {
        val source = MockMessageSource()
        val provider = replyingPage(source)
        source.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))

        val reply = provider.mirror().actions.single { it.functionId == FunctionId.SELECT }

        assertEquals(MirrorActionToken.REPLY_MESSAGE, reply.token)
        assertEquals(MirrorSlot.PRIMARY, reply.slot)
        assertFalse(reply.enabled)
    }

    @Test
    fun `mirror declares the reply enabled when the app can reply`() {
        val source = MockMessageSource()
        val provider = replyingPage(source)
        source.setMessage(MessageInfo(sender = "Elena", body = "Are you far?"))
        source.setCanReply(true)

        val reply = provider.mirror().actions.single { it.functionId == FunctionId.SELECT }

        assertTrue(reply.enabled)
    }

    /** The button, the key and the mirror zone read one property, so they cannot disagree. */
    @Test
    fun `redraws when the reply becomes possible`() = runTest {
        val source = MockMessageSource()
        val provider = replyingPage(source)
        val ticks = mutableListOf<Unit>()
        val job = launch { provider.invalidations.collect { ticks += Unit } }
        delay(50.milliseconds)

        source.setCanReply(true)
        delay(50.milliseconds)
        job.cancel()

        assertEquals(1, ticks.size)
    }

    /**
     * Coming back from the reply page is a remount, and a remount must not read the body again.
     *
     * Replying makes the app repost the notification, and the reading is "once per message". Had
     * the live reply action been carried inside [MessageInfo], the reposted message would not equal
     * the one already read and the cluster would start the body over the moment the rider sent an
     * answer.
     */
    @Test
    fun `does not read the body again after coming back from the reply page`() = runTest {
        val source = MockMessageSource()
        val provider = replyingPage(source)
        val scripts = mutableListOf<List<SpeechRequest>>()
        val job = launch { provider.speech.collect { scripts += it } }
        val message = MessageInfo(sender = "Elena", body = "Are you far?")
        source.setMessage(message)
        source.setCanReply(true)
        delay(50.milliseconds)

        // The reply goes out, the app reposts the very same message, and the page is mounted again.
        provider.onInput(FunctionId.SELECT)
        source.setMessage(message)
        delay(50.milliseconds)
        job.cancel()

        assertEquals(1, scripts.size)
    }

    @Test
    fun `should take its header and its nameless sender from the resolver`() {
        val messageSource = MockMessageSource()
        val provider = MessagePageProvider(
            messageSource.currentMessage,
            copy = { "[${it.name}]" },
            messageSource = messageSource
        )
        messageSource.setMessage(MessageInfo(sender = "", body = "Hi"))

        val frame = provider.render(1) as ProjectionFrame.LiveScreen

        assertEquals("[HEADER_MESSAGES]", frame.headerText)
        assertEquals("[HEADER_MESSAGES]", frame.contentTextLine1)
    }

    @Test
    fun `should take both lines of its standby from the resolver`() {
        val messageSource = MockMessageSource()
        val provider = MessagePageProvider(
            messageSource.currentMessage,
            copy = { "[${it.name}]" },
            messageSource = messageSource
        )

        val frame = provider.render(1) as ProjectionFrame.LiveScreen

        assertEquals("[HEADER_MESSAGES]", frame.headerText)
        assertEquals("[EMPTY_MESSAGES]", frame.contentTextLine1)
    }
}
