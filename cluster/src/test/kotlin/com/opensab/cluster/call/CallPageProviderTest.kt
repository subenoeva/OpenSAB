package com.opensab.cluster.call
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallStatus
import com.opensab.domain.call.MockTelecomAdapter
import com.opensab.domain.call.RecordingTelecomAdapter
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.music.VolumeLevel
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallPageProviderTest {

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
    fun `renders ringing call with two buttons (decline and answer) and no content icon`() {
        val telecom = MockTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom)

        telecom.setIncomingCall(CallInfo(callerName = "Alice", callerNumber = "+123456789", status = CallStatus.RINGING))

        val frame = provider.render(requestId = 200) as ProjectionFrame.LiveScreen
        assertEquals(200, frame.requestId)
        assertEquals("Phone", frame.headerText)
        assertEquals(ColorId.PURPLE, frame.headerBgColorId)
        assertEquals("Alice", frame.contentTextLine1)
        assertEquals("Ringing", frame.contentTextLine2)
        // The status line takes the place of the icon: a live screen carries one or the other.
        assertEquals(IconId.IC_NONE, frame.contentIconId)
        assertEquals(IconId.LIVE_HANG_UP_TWO_BUTTONS, frame.leftActionContentIconId)
        assertEquals(ColorId.NEGATIVE, frame.leftActionBgColorId)
        assertEquals(IconId.LIVE_CALL, frame.rightActionContentIconId)
        assertEquals(ColorId.POSITIVE, frame.rightActionBgColorId)
    }

    @Test
    fun `renders active call with caller name and status in purple live screen`() {
        val telecom = MockTelecomAdapter()
        val volumeSource = TestVolumeSource()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom, volumeSource)

        telecom.setIncomingCall(CallInfo(callerName = "Alice", callerNumber = "+123456789", status = CallStatus.RINGING))
        telecom.acceptCall()

        val frame = provider.render(requestId = 201) as ProjectionFrame.LiveScreen
        assertEquals(201, frame.requestId)
        assertEquals("Phone", frame.headerText)
        assertEquals(ColorId.PURPLE, frame.headerBgColorId)
        assertEquals("Alice", frame.contentTextLine1)
        assertEquals("In Call", frame.contentTextLine2)
        assertEquals(IconId.IC_NONE, frame.contentIconId)
        assertEquals(IconId.LIVE_HANG_UP_ONE_BUTTON, frame.leftActionContentIconId)
    }

    @Test
    fun `renders standby screen when no call is active`() {
        val telecom = MockTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom)

        val frame = provider.render(requestId = 202) as ProjectionFrame.LiveScreen
        assertEquals(202, frame.requestId)
        assertEquals("Phone", frame.headerText)
        assertEquals(ColorId.PURPLE, frame.headerBgColorId)
        assertEquals(IconId.CALL, frame.contentIconId)
        assertEquals("No Call", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun `back button hangs up active call and pops back to root menu`() {
        val telecom = MockTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom)

        telecom.setIncomingCall(CallInfo(callerName = "Bob", callerNumber = "+987654321", status = CallStatus.RINGING))
        telecom.acceptCall()

        val action = provider.onInput(FunctionId.BACK)
        assertEquals(NavigationAction.Pop, action)
        assertEquals(CallStatus.DISCONNECTED, telecom.currentCall.value?.status)
    }

    @Test
    fun `back button rejects incoming ringing call and pops back to root menu`() {
        val telecom = MockTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom)

        telecom.setIncomingCall(CallInfo(callerName = "Bob", callerNumber = "+987654321", status = CallStatus.RINGING))

        val action = provider.onInput(FunctionId.BACK)
        assertEquals(NavigationAction.Pop, action)
        assertEquals(CallStatus.DISCONNECTED, telecom.currentCall.value?.status)
    }

    @Test
    fun `select button answers call when ringing`() {
        val telecom = MockTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom)

        telecom.setIncomingCall(CallInfo(callerName = "Bob", callerNumber = "+987654321", status = CallStatus.RINGING))

        val action = provider.onInput(FunctionId.SELECT)
        assertEquals(NavigationAction.None, action)
        assertEquals(CallStatus.ACTIVE, telecom.currentCall.value?.status)
    }

    @Test
    fun `next button lowers volume while ringing and never answers the call`() {
        val telecom = MockTelecomAdapter()
        val volumeSource = TestVolumeSource()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom, volumeSource)

        telecom.setIncomingCall(CallInfo(callerName = "Bob", callerNumber = "+987654321", status = CallStatus.RINGING))

        val action = provider.onInput(FunctionId.NEXT)
        assertEquals(NavigationAction.None, action)
        assertEquals(CallStatus.RINGING, telecom.currentCall.value?.status)
        assertEquals(1, volumeSource.lowerCalls)
    }

    @Test
    fun `menu button resets back to root menu`() {
        val telecom = MockTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom)

        val action = provider.onInput(FunctionId.MENU)
        assertEquals(NavigationAction.Reset, action)
    }

    @Test
    fun `previous and volume up buttons raise volume`() {
        val telecom = MockTelecomAdapter()
        val volumeSource = TestVolumeSource()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom, volumeSource)

        provider.onInput(FunctionId.PREVIOUS)
        assertEquals(1, volumeSource.raiseCalls)

        provider.onInput(FunctionId.VOLUME_UP)
        assertEquals(2, volumeSource.raiseCalls)
    }

    @Test
    fun `next and volume down buttons lower volume`() {
        val telecom = MockTelecomAdapter()
        val volumeSource = TestVolumeSource()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom, volumeSource)

        provider.onInput(FunctionId.NEXT)
        assertEquals(1, volumeSource.lowerCalls)

        provider.onInput(FunctionId.VOLUME_DOWN)
        assertEquals(2, volumeSource.lowerCalls)
    }

    @Test
    fun `volume mute button toggles mute`() {
        val telecom = MockTelecomAdapter()
        val volumeSource = TestVolumeSource()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom, volumeSource)

        provider.onInput(FunctionId.VOLUME_MUTE)
        assertEquals(1, volumeSource.muteCalls)
    }

    @Test
    fun `retains state on resume`() {
        val telecom = MockTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom)

        assertEquals(ResumeBehavior.RETAIN, provider.onResumeBehavior)
        assertEquals("CallPage", provider.name)
    }

    // -------------------------------------------------------------------------------------------
    // One owner for the press
    // -------------------------------------------------------------------------------------------

    /**
     * A second collector inside `DefaultCallManager` once answered on `NEXT`, which is volume down.
     * Counting the adapter whole, and not only `acceptCall()`, keeps the assertion true whichever telephony method
     * a reintroduced route reaches for.
     */
    @Test
    fun `next while the call rings lowers the volume and reaches no telephony method`() {
        val telecom = RecordingTelecomAdapter()
        val volumeSource = RecordingVolumeSource()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom, volumeSource)

        telecom.setIncomingCall(CallInfo(callerName = "Bob", callerNumber = "+987654321"))

        val action = provider.onInput(FunctionId.NEXT)
        assertEquals(NavigationAction.None, action)
        assertEquals(1, volumeSource.lowerCalls)
        assertEquals(0, telecom.totalCalls)
        assertEquals(CallStatus.RINGING, telecom.currentCall.value?.status)
    }

    /**
     * V-3: with the page mounted and the adapter collecting the same event, one press produced two
     * `acceptCall()`. The page on its own answers once and moves nothing else on the adapter.
     */
    @Test
    fun `select while the call rings answers exactly once`() {
        val telecom = RecordingTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom, RecordingVolumeSource())

        telecom.setIncomingCall(CallInfo(callerName = "Bob", callerNumber = "+987654321"))

        val action = provider.onInput(FunctionId.SELECT)
        assertEquals(NavigationAction.None, action)
        assertEquals(1, telecom.acceptCalls)
        assertEquals(1, telecom.totalCalls)
        assertEquals(CallStatus.ACTIVE, telecom.currentCall.value?.status)
    }

    /**
     * Outside `RINGING` there is nothing to answer, so the branch returns `None` without reaching the
     * adapter — which is what lets [MirrorActionToken.ACCEPT_CALL] stay declared and disabled instead
     * of vanishing. Both states are asserted together because a second input route would answer in
     * both of them alike.
     */
    @Test
    fun `select on an active call and on standby answers nothing`() {
        val onCall = RecordingTelecomAdapter()
        onCall.setIncomingCall(CallInfo(callerName = "Bob", callerNumber = "+987654321"))
        onCall.acceptCall()
        onCall.resetCounts()
        val active = CallPageProvider(onCall.currentCall, englishClusterText, onCall, RecordingVolumeSource())

        assertEquals(NavigationAction.None, active.onInput(FunctionId.SELECT))
        assertEquals(0, onCall.totalCalls)
        assertEquals(CallStatus.ACTIVE, onCall.currentCall.value?.status)

        val idle = RecordingTelecomAdapter()
        val standby = CallPageProvider(idle.currentCall, englishClusterText, idle, RecordingVolumeSource())

        assertEquals(NavigationAction.None, standby.onInput(FunctionId.SELECT))
        assertEquals(0, idle.totalCalls)
        assertEquals(null, idle.currentCall.value)
    }

    /** `BACK` is the only binding that turns the call down, and it does it once. */
    @Test
    fun `back while the call rings rejects exactly once`() {
        val telecom = RecordingTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom, RecordingVolumeSource())

        telecom.setIncomingCall(CallInfo(callerName = "Bob", callerNumber = "+987654321"))

        val action = provider.onInput(FunctionId.BACK)
        assertEquals(NavigationAction.Pop, action)
        assertEquals(1, telecom.rejectCalls)
        assertEquals(1, telecom.totalCalls)
        assertEquals(CallStatus.DISCONNECTED, telecom.currentCall.value?.status)
    }

    // -------------------------------------------------------------------------------------------
    // Mirror
    // -------------------------------------------------------------------------------------------

    /**
     * The volume block and the escape hatch, identical in the three states of the page.
     * `VOLUME_MUTE` sits on `RIGHT` because `LEFT` already carries `BACK`.
     */
    private val sharedActions = listOf(
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

    private fun ringingProvider(): CallPageProvider {
        val telecom = MockTelecomAdapter()
        telecom.setIncomingCall(CallInfo(callerName = "Alice", callerNumber = "+123456789"))
        return CallPageProvider(telecom.currentCall, englishClusterText, telecom, TestVolumeSource())
    }

    private fun activeProvider(): CallPageProvider {
        val telecom = MockTelecomAdapter()
        telecom.setIncomingCall(CallInfo(callerName = "Alice", callerNumber = "+123456789"))
        telecom.acceptCall()
        return CallPageProvider(telecom.currentCall, englishClusterText, telecom, TestVolumeSource())
    }

    /** While it rings, `BACK` turns the call down and `SELECT` is the only binding that answers. */
    @Test
    fun `mirror offers reject and an enabled accept while the call is ringing`() {
        val page = ringingProvider().mirror()

        assertEquals("CallPage", page.pageName)
        assertEquals(
            listOf(
                MirrorAction(
                    token = MirrorActionToken.REJECT_CALL,
                    functionId = FunctionId.BACK,
                    slot = MirrorSlot.LEFT
                ),
                MirrorAction(
                    token = MirrorActionToken.ACCEPT_CALL,
                    functionId = FunctionId.SELECT,
                    slot = MirrorSlot.PRIMARY
                )
            ) + sharedActions,
            page.actions
        )
    }

    /**
     * Once the call is up there is nothing left to accept: `onInput(SELECT)` returns `None` without
     * touching the adapter, so the zone stays declared but disabled — it holds its slot showing the
     * rider that right now it leads nowhere, rather than letting the layout shift under a glance.
     */
    @Test
    fun `mirror hangs up and disables accept once the call is active`() {
        val page = activeProvider().mirror()

        assertEquals(
            listOf(
                MirrorAction(
                    token = MirrorActionToken.HANG_UP,
                    functionId = FunctionId.BACK,
                    slot = MirrorSlot.LEFT
                ),
                MirrorAction(
                    token = MirrorActionToken.ACCEPT_CALL,
                    functionId = FunctionId.SELECT,
                    slot = MirrorSlot.PRIMARY,
                    enabled = false
                )
            ) + sharedActions,
            page.actions
        )
    }

    /**
     * With no call at all, `BACK` still returns `Pop`, so the zone is genuinely enabled; its token
     * falls to [MirrorActionToken.HANG_UP] because that is the state the frame paints.
     */
    @Test
    fun `mirror keeps back enabled on standby and still disables accept`() {
        val telecom = MockTelecomAdapter()
        val page = CallPageProvider(telecom.currentCall, englishClusterText, telecom, TestVolumeSource()).mirror()

        val back = page.actions.single { it.functionId == FunctionId.BACK }
        assertEquals(MirrorActionToken.HANG_UP, back.token)
        assertEquals(true, back.enabled)

        val accept = page.actions.single { it.functionId == FunctionId.SELECT }
        assertEquals(MirrorActionToken.ACCEPT_CALL, accept.token)
        assertEquals(false, accept.enabled)
    }

    /** The mirror twins the projected frame rather than wording the page a second time. */
    @Test
    fun `mirror twins the projected frame`() {
        val provider = ringingProvider()
        val frame = provider.render(requestId = 0) as ProjectionFrame.LiveScreen
        val page = provider.mirror()

        assertEquals(frame.headerText, page.title)
        assertEquals(listOf("Alice", "Ringing"), page.lines)
        assertEquals(frame.headerBgColorId, page.accent)
    }

    @Test
    fun `should take its header and its nameless caller from the resolver`() {
        val call = MutableStateFlow<CallInfo?>(CallInfo(callerName = "", callerNumber = "", status = CallStatus.ACTIVE))
        val page = CallPageProvider(call, copy = { "[${it.name}]" })

        val frame = page.render(1) as ProjectionFrame.LiveScreen

        assertEquals("[HEADER_PHONE]", frame.headerText)
        assertEquals("[HEADER_PHONE]", frame.contentTextLine1)
    }

    @Test
    fun `should take its three status lines from the resolver`() {
        val call = MutableStateFlow<CallInfo?>(null)
        val page = CallPageProvider(call, copy = { "[${it.name}]" })

        val lines = listOf(CallStatus.RINGING, CallStatus.ACTIVE, CallStatus.DISCONNECTED).map {
            call.value = CallInfo(callerName = "Ana", callerNumber = "600", status = it)
            (page.render(1) as ProjectionFrame.LiveScreen).contentTextLine2
        }

        assertEquals(listOf("[CALL_RINGING]", "[CALL_IN_CALL]", "[CALL_ENDED]"), lines)
    }

    @Test
    fun `should take both lines of its standby from the resolver`() {
        val call = MutableStateFlow<CallInfo?>(null)
        val page = CallPageProvider(call, copy = { "[${it.name}]" })

        val frame = page.render(1) as ProjectionFrame.LiveScreen

        assertEquals("[HEADER_PHONE]", frame.headerText)
        assertEquals("[EMPTY_CALL]", frame.contentTextLine1)
    }

    @Test
    fun `speech emits CALL_RINGING with caller name when activeCall enters RINGING`() = runTest {
        val telecom = MockTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom)
        val spoken = mutableListOf<List<SpeechRequest>>()
        backgroundScope.launch {
            provider.speech.collect { spoken.add(it) }
        }
        runCurrent()
        assertTrue(spoken.isEmpty())

        telecom.setIncomingCall(CallInfo(callerName = "Alice", callerNumber = "+123456789", status = CallStatus.RINGING))
        runCurrent()

        assertEquals(1, spoken.size)
        val request = spoken.single().single()
        assertEquals(SpeechToken.CALL_RINGING, request.token)
        assertEquals(listOf(SpeechArg.Text("Alice")), request.args)
    }

    @Test
    fun `speech emits CALL_RINGING with caller number when name is empty`() = runTest {
        val telecom = MockTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom)
        val spoken = mutableListOf<List<SpeechRequest>>()
        backgroundScope.launch {
            provider.speech.collect { spoken.add(it) }
        }
        runCurrent()

        telecom.setIncomingCall(CallInfo(callerName = "", callerNumber = "+123456789", status = CallStatus.RINGING))
        runCurrent()

        assertEquals(1, spoken.size)
        val request = spoken.single().single()
        assertEquals(SpeechToken.CALL_RINGING, request.token)
        assertEquals(listOf(SpeechArg.Digits("+123456789")), request.args)
    }

    @Test
    fun `speech emits CALL_RINGING_UNKNOWN when caller name is empty and number has no digits`() = runTest {
        val telecom = MockTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom)
        val spoken = mutableListOf<List<SpeechRequest>>()
        backgroundScope.launch {
            provider.speech.collect { spoken.add(it) }
        }
        runCurrent()

        telecom.setIncomingCall(CallInfo(callerName = "", callerNumber = "++--", status = CallStatus.RINGING))
        runCurrent()

        assertEquals(1, spoken.size)
        val request = spoken.single().single()
        assertEquals(SpeechToken.CALL_RINGING_UNKNOWN, request.token)
        assertEquals(emptyList<SpeechArg>(), request.args)
    }

    @Test
    fun `speech emits CALL_RINGING_UNKNOWN when caller name and number are empty`() = runTest {
        val telecom = MockTelecomAdapter()
        val provider = CallPageProvider(telecom.currentCall, englishClusterText, telecom)
        val spoken = mutableListOf<List<SpeechRequest>>()
        backgroundScope.launch {
            provider.speech.collect { spoken.add(it) }
        }
        runCurrent()

        telecom.setIncomingCall(CallInfo(callerName = "", callerNumber = "", status = CallStatus.RINGING))
        runCurrent()

        assertEquals(1, spoken.size)
        val request = spoken.single().single()
        assertEquals(SpeechToken.CALL_RINGING_UNKNOWN, request.token)
        assertTrue(request.args.isEmpty())
    }

    @Test
    fun `speech deduplicates while call continues ringing`() = runTest {
        val callFlow = MutableStateFlow<CallInfo?>(null)
        val provider = CallPageProvider(callFlow, englishClusterText)
        val spoken = mutableListOf<List<SpeechRequest>>()
        backgroundScope.launch {
            provider.speech.collect { spoken.add(it) }
        }
        runCurrent()

        val ringingCall = CallInfo(callerName = "Alice", callerNumber = "+123", status = CallStatus.RINGING)
        callFlow.value = ringingCall
        runCurrent()
        assertEquals(1, spoken.size)

        // Same call emitted again
        callFlow.value = ringingCall.copy()
        runCurrent()
        assertEquals(1, spoken.size)
    }

    @Test
    fun `speech resets announced call when call stops ringing and announces when new ringing call arrives`() = runTest {
        val callFlow = MutableStateFlow<CallInfo?>(null)
        val provider = CallPageProvider(callFlow, englishClusterText)
        val spoken = mutableListOf<List<SpeechRequest>>()
        backgroundScope.launch {
            provider.speech.collect { spoken.add(it) }
        }
        runCurrent()

        val firstCall = CallInfo(callerName = "Alice", callerNumber = "+123", status = CallStatus.RINGING)
        callFlow.value = firstCall
        runCurrent()
        assertEquals(1, spoken.size)

        // Call accepted -> ACTIVE
        callFlow.value = firstCall.copy(status = CallStatus.ACTIVE)
        runCurrent()
        assertEquals(1, spoken.size)

        // Call ended -> null
        callFlow.value = null
        runCurrent()
        assertEquals(1, spoken.size)

        // Next call arrives -> rings again
        val secondCall = CallInfo(callerName = "Bob", callerNumber = "+456", status = CallStatus.RINGING)
        callFlow.value = secondCall
        runCurrent()
        assertEquals(2, spoken.size)
        assertEquals("Bob", (spoken[1].single().args.single() as SpeechArg.Text).value)
    }
}
