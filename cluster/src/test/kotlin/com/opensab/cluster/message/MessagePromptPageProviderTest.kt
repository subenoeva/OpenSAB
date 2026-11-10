package com.opensab.cluster.message
import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** The page that asks before reading: a window saying a message arrived, with two ways out. */
class MessagePromptPageProviderTest {

    private fun message(
        sender: String = "Carlos",
        body: String = "Llego en 10 minutos",
        appName: String? = "WhatsApp",
        timestamp: Long = 1_000L
    ) = MessageInfo(sender = sender, body = body, appName = appName, timestamp = timestamp)

    private fun readPage() = StubPageProvider("StubReadPage", LiveScreenPresets.idle(1))

    private fun page(
        source: MockMessageSource,
        copy: ClusterTextResolver = englishClusterText,
        volume: RecordingVolumeSource? = null,
        readPage: PageProvider = readPage()
    ) = MessagePromptPageProvider(
        currentMessage = source.currentMessage,
        copy = copy,
        messageSource = source,
        volumeSource = volume,
        readPageFactory = { readPage }
    )

    // -----------------------------------------------------------------------------------------
    // The frame
    // -----------------------------------------------------------------------------------------

    @Test
    fun `renders the sender and the app name with both buttons lit`() {
        val source = MockMessageSource()
        val provider = page(source)
        source.setMessage(message())

        val frame = provider.render(requestId = 501) as ProjectionFrame.LiveScreen

        assertEquals(501, frame.requestId)
        assertEquals("Messages", frame.headerText)
        assertEquals(ColorId.BLUE, frame.headerBgColorId)
        assertEquals("Carlos", frame.contentTextLine1)
        assertEquals("WhatsApp", frame.contentTextLine2)
        assertEquals(IconId.LIVE_CANCEL_TWO_BUTTONS, frame.leftActionContentIconId)
        assertEquals(IconId.LIVE_PLAY, frame.rightActionContentIconId)
    }

    @Test
    fun `renders the standby frame when there is no message`() {
        val provider = page(MockMessageSource())

        val frame = provider.render(requestId = 502) as ProjectionFrame.LiveScreen

        assertEquals("No Messages", frame.contentTextLine1)
        assertEquals(IconId.MESSAGE, frame.contentIconId)
    }

    /** A null app name is a package outside the table; the line goes empty and the icon returns. */
    @Test
    fun `renders the message icon when the package carries no label`() {
        val source = MockMessageSource()
        val provider = page(source)
        source.setMessage(message(appName = null))

        val frame = provider.render(requestId = 503) as ProjectionFrame.LiveScreen

        assertEquals("", frame.contentTextLine2)
        assertEquals(IconId.MESSAGE, frame.contentIconId)
    }

    // -----------------------------------------------------------------------------------------
    // The keys
    // -----------------------------------------------------------------------------------------

    /**
     * `@Back Ignore` is the `YIELD` with `PageArgs.q(MESSAGE).y0(true)`: it gives the screen back
     * and leaves the message unread, so the bit stays lit and the carousel entry stays open.
     */
    @Test
    fun `ignore yields the screen and pops`() {
        val source = MockMessageSource()
        val provider = page(source)
        source.setMessage(message())

        val action = provider.onInput(FunctionId.BACK)

        assertEquals(NavigationAction.Pop, action)
        assertEquals(1, source.yieldCalls)
        assertTrue("the message must survive Ignore", source.currentMessage.value != null)
    }

    @Test
    fun `menu yields the screen and resets`() {
        val source = MockMessageSource()
        val provider = page(source)
        source.setMessage(message())

        val action = provider.onInput(FunctionId.MENU)

        assertEquals(NavigationAction.Reset, action)
        assertEquals(1, source.yieldCalls)
    }

    @Test
    fun `play opens the read page`() {
        val source = MockMessageSource()
        val readPage = readPage()
        val provider = page(source, readPage = readPage)
        source.setMessage(message())

        val action = provider.onInput(FunctionId.SELECT)

        assertTrue(action is NavigationAction.Next)
        assertSame(readPage, (action as NavigationAction.Next).page)
    }

    /** Playing is not yielding: the message keeps the screen, it just changes page. */
    @Test
    fun `play does not yield the screen`() {
        val source = MockMessageSource()
        val provider = page(source)
        source.setMessage(message())

        provider.onInput(FunctionId.SELECT)

        assertEquals(0, source.yieldCalls)
    }

    /** The read page is one instance, so two presses do not hand over two identities. */
    @Test
    fun `play hands over the same read page every time`() {
        val source = MockMessageSource()
        val provider = page(source)
        source.setMessage(message())

        val first = provider.onInput(FunctionId.SELECT) as NavigationAction.Next
        val second = provider.onInput(FunctionId.SELECT) as NavigationAction.Next

        assertSame(first.page, second.page)
    }

    @Test
    fun `play leads nowhere while there is no message`() {
        val provider = page(MockMessageSource())

        assertEquals(NavigationAction.None, provider.onInput(FunctionId.SELECT))
    }

    @Test
    fun `volume keys reach the volume source through both namings`() {
        val source = MockMessageSource()
        val volume = RecordingVolumeSource()
        val provider = page(source, volume = volume)
        source.setMessage(message())

        provider.onInput(FunctionId.PREVIOUS)
        provider.onInput(FunctionId.VOLUME_UP)
        provider.onInput(FunctionId.NEXT)
        provider.onInput(FunctionId.VOLUME_DOWN)
        provider.onInput(FunctionId.VOLUME_MUTE)

        assertEquals(2, volume.raiseCalls)
        assertEquals(2, volume.lowerCalls)
        assertEquals(1, volume.muteCalls)
    }

    // -----------------------------------------------------------------------------------------
    // The voice
    // -----------------------------------------------------------------------------------------

    @Test
    fun `asks to listen naming the sender and the app`() = runTest {
        val source = MockMessageSource()
        val provider = page(source)
        source.setMessage(message())

        val script = provider.speech.first()

        assertEquals(listOf(SpeechToken.MESSAGE_PROMPT), script.map { it.token })
        assertEquals(
            listOf(SpeechArg.Text("Carlos"), SpeechArg.Text("WhatsApp")),
            script.single().args
        )
    }

    /**
     * A package with no label in `MessagingPackages` takes the shorter question. Building the long
     * one with an empty second argument would say "Mensaje de Carlos por . ¿Te lo leo?", and
     * building it with one argument would throw on the bike: `SpeechRequest` checks arity.
     */
    @Test
    fun `drops the app from the question when the package has no label`() = runTest {
        val source = MockMessageSource()
        val provider = page(source)
        source.setMessage(message(appName = null))

        val script = provider.speech.first()

        assertEquals(listOf(SpeechToken.MESSAGE_PROMPT_SHORT), script.map { it.token })
        assertEquals(listOf(SpeechArg.Text("Carlos")), script.single().args)
    }

    /** The next message of the thread is a new question, not a repeat of the old one. */
    @Test
    fun `asks again when the next message of the thread arrives`() = runTest {
        val source = MockMessageSource()
        val provider = page(source)
        source.setMessage(message(body = "Llego en 10 minutos", timestamp = 1_000L))
        provider.speech.first()

        source.setMessage(message(sender = "Elena", body = "Voy saliendo", timestamp = 2_000L))

        assertEquals(
            listOf(SpeechArg.Text("Elena"), SpeechArg.Text("WhatsApp")),
            provider.speech.first().single().args
        )
    }

    /**
     * Coming back from a call that covered the page is a remount, and the rider has already been
     * asked, so it must not be asked again.
     */
    @Test
    fun `does not ask again when the page is remounted for the same message`() = runTest {
        val source = MockMessageSource()
        val provider = page(source)
        source.setMessage(message())
        provider.speech.first()

        assertNull(withTimeoutOrNull(1.seconds) { provider.speech.first() })
    }

    @Test
    fun `a page with no message asks nothing`() = runTest {
        val provider = page(MockMessageSource())

        assertNull(withTimeoutOrNull(1.seconds) { provider.speech.first() })
    }

    // -----------------------------------------------------------------------------------------
    // The mirror
    // -----------------------------------------------------------------------------------------

    @Test
    fun `declares read message and dismiss message`() {
        val source = MockMessageSource()
        val provider = page(source)
        source.setMessage(message())

        val actions = provider.mirror().actions.associateBy { it.token }

        val read = actions.getValue(MirrorActionToken.READ_MESSAGE)
        assertEquals(FunctionId.SELECT, read.functionId)
        assertEquals(MirrorSlot.PRIMARY, read.slot)
        assertTrue("reading is available while there is a message", read.enabled)

        val dismiss = actions.getValue(MirrorActionToken.DISMISS_MESSAGE)
        assertEquals(FunctionId.BACK, dismiss.functionId)
        assertEquals(MirrorSlot.LEFT, dismiss.slot)
    }

    /**
     * On standby the key leads nowhere, so the zone holds its slot disabled rather than vanishing
     * and shifting the rest — the same shape `ACCEPT_CALL` takes outside RINGING.
     */
    @Test
    fun `disables read message on standby`() {
        val provider = page(MockMessageSource())

        val read = provider.mirror().actions.single { it.token == MirrorActionToken.READ_MESSAGE }

        assertFalse(read.enabled)
    }

    @Test
    fun `retains itself across an interruption`() {
        val provider = page(MockMessageSource())

        assertEquals(ResumeBehavior.RETAIN, provider.onResumeBehavior)
    }

    @Test
    fun `should take its header and its nameless sender from the resolver`() {
        val source = MockMessageSource()
        val provider = page(source, copy = { "[${it.name}]" })

        source.setMessage(message(sender = ""))
        val liveFrame = provider.render(1) as ProjectionFrame.LiveScreen
        assertEquals("[HEADER_MESSAGES]", liveFrame.headerText)
        assertEquals("[HEADER_MESSAGES]", liveFrame.contentTextLine1)

        val standbyProvider = page(MockMessageSource(), copy = { "[${it.name}]" })
        val standbyFrame = standbyProvider.render(2) as ProjectionFrame.LiveScreen
        assertEquals("[HEADER_MESSAGES]", standbyFrame.headerText)
        assertEquals("[EMPTY_MESSAGES]", standbyFrame.contentTextLine1)
    }
}
