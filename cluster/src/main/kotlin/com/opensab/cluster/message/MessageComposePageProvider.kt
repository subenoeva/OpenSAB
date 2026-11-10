package com.opensab.cluster.message

import com.opensab.cluster.CuingPage
import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.WorkingPage
import com.opensab.cluster.clusterLineFor
import com.opensab.cluster.cueEmitter
import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.cluster.redrawOn
import com.opensab.cluster.spokenFailureFor
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.ClusterToken
import com.opensab.cluster.text.TextBudget
import com.opensab.domain.audio.Cue
import com.opensab.domain.contacts.CallTarget
import com.opensab.domain.message.SmsSender
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.speech.RecognitionFailure
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRecognitionException
import com.opensab.domain.speech.SpeechRecognizer
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.logging.Log
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * The faces of [MessageComposePageProvider] for outbound message composition and delivery.
 */
internal sealed interface MessageComposeState {

    /** The microphone is open listening for dictated text. */
    data object Listening : MessageComposeState

    /** [text] was understood and is reviewed via TTS and on the cluster screen. */
    data class Confirming(val text: String) : MessageComposeState

    /** The microphone opened and nothing was understood or silence was captured. */
    data object NothingUnderstood : MessageComposeState

    /** The dictation failed for an actionable reason. */
    data class Failed(val reason: RecognitionFailure) : MessageComposeState

    /** The quick replies list, with cursor on [index], returning to [from] on back. */
    data class Choosing(val index: Int, val from: MessageComposeState) : MessageComposeState

    /** [text] is being transmitted through telephony via [SmsSender]. */
    data class Sending(val text: String) : MessageComposeState

    /** The message was confirmed sent by telephony layer. */
    data object Sent : MessageComposeState

    /** The transmission failed and [text] is kept for retry. */
    data class NotSent(val text: String) : MessageComposeState
}

/**
 * Outbound message dictation, TTS confirmation, quick replies, and SMS transmission.
 *
 * Input Mapping:
 * - MENU -> Reset to carousel root across all faces
 * - BACK -> Cancel listen and Pop while listening; retry/back on other faces
 * - SELECT -> Send from confirmation, quick reply, or retry from failed transmission; open quick list if idle
 * - PREVIOUS / NEXT -> Navigate quick replies list when active, adjust volume on all other faces
 * - VOLUME_MUTE -> Toggle mute across all faces
 */
internal class MessageComposePageProvider(
    private val target: CallTarget,
    private val recognizer: SpeechRecognizer,
    private val smsSender: SmsSender,
    private val sentLinger: StateFlow<Duration>,
    private val copy: ClusterTextResolver,
    private val quickReplies: StateFlow<List<String>> = MutableStateFlow(emptyList()),
    private val volumeSource: VolumeSource? = null
) : SpeakingPage, WorkingPage, CuingPage {

    override val name: String = "MessageComposePage"

    private val _cues = cueEmitter()

    override val cues: Flow<Cue> = _cues.asSharedFlow()

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.DISCARD

    private val _state = MutableStateFlow<MessageComposeState>(MessageComposeState.Listening)

    val state: StateFlow<MessageComposeState> = _state.asStateFlow()

    override val speech: Flow<List<SpeechRequest>> = _state
        .map { face ->
            when (face) {
                is MessageComposeState.Confirming -> listOf(SpeechRequest(SpeechToken.MESSAGE_CONFIRM, SpeechArg.Text(face.text)))
                MessageComposeState.NothingUnderstood -> listOf(SpeechRequest(SpeechToken.VOICE_NOTHING_UNDERSTOOD))
                is MessageComposeState.Failed -> listOf(SpeechRequest(spokenFailureFor(face.reason, SpeechToken.VOICE_FAILED)))
                is MessageComposeState.Choosing -> {
                    val reply = quickReplies.value.getOrNull(face.index).orEmpty()
                    listOf(SpeechRequest(SpeechToken.MESSAGE_CHOOSING_REPLY, SpeechArg.Text(reply)))
                }
                MessageComposeState.Sent -> listOf(SpeechRequest(SpeechToken.MESSAGE_SENT))
                is MessageComposeState.NotSent -> listOf(SpeechRequest(SpeechToken.MESSAGE_NOT_SENT))
                MessageComposeState.Listening,
                is MessageComposeState.Sending -> emptyList()
            }
        }

    override val invalidations: Flow<Unit> = redrawOn(_state, quickReplies)

    override suspend fun work(): NavigationAction = coroutineScope {
        val outcome = CompletableDeferred<NavigationAction>()
        val runner = launch {
            _state.collectLatest { face -> advance(face, outcome) }
        }
        val action = outcome.await()
        runner.cancel()
        action
    }

    private suspend fun advance(face: MessageComposeState, outcome: CompletableDeferred<NavigationAction>) {
        when (face) {
            MessageComposeState.Listening -> listenOnce()

            is MessageComposeState.Sending -> send(face.text)

            MessageComposeState.Sent -> {
                delay(sentLinger.value)
                Log.d("Outbound SMS delivered and lingered, resetting to carousel root")
                outcome.complete(NavigationAction.Reset)
            }

            is MessageComposeState.Confirming,
            MessageComposeState.NothingUnderstood,
            is MessageComposeState.Failed,
            is MessageComposeState.Choosing,
            is MessageComposeState.NotSent -> Unit
        }
    }

    private suspend fun listenOnce() {
        _cues.tryEmit(Cue.LISTEN)
        val heard = try {
            recognizer.listen()
        } catch (e: SpeechRecognitionException) {
            Log.w("Dictation failed: %s", e.reason)
            _cues.tryEmit(Cue.FAILURE)
            _state.value = MessageComposeState.Failed(e.reason)
            return
        }
        _state.value = if (heard.isNullOrBlank()) {
            _cues.tryEmit(Cue.FAILURE)
            MessageComposeState.NothingUnderstood
        } else {
            MessageComposeState.Confirming(heard)
        }
    }

    private suspend fun send(text: String) {
        val sent = smsSender.send(target.number, text)
        _cues.tryEmit(if (sent) Cue.SUCCESS else Cue.FAILURE)
        _state.value = if (sent) MessageComposeState.Sent else MessageComposeState.NotSent(text)
    }

    override fun render(requestId: Int): ProjectionFrame = when (val face = _state.value) {
        MessageComposeState.Listening -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = copy.resolve(ClusterToken.REPLY_SAY_YOUR_MESSAGE),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_CANCEL_ONE_BUTTON,
            rightActionIcon = quickListIcon()
        )

        is MessageComposeState.Confirming -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = truncateToLineBudget(face.text),
            contentIconId = IconId.REVIEW,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = IconId.LIVE_SEND,
            rightActionBgColorId = ColorId.POSITIVE
        )

        MessageComposeState.NothingUnderstood -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = copy.resolve(ClusterToken.LISTEN_NOTHING_UNDERSTOOD),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = quickListIcon()
        )

        is MessageComposeState.Failed -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = copy.resolve(clusterLineFor(face.reason)),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = quickListIcon()
        )

        is MessageComposeState.Choosing -> chooseFrame(requestId, face)

        is MessageComposeState.Sending -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = copy.resolve(ClusterToken.REPLY_SENDING),
            contentIconId = IconId.LOADING
        )

        MessageComposeState.Sent -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = copy.resolve(ClusterToken.REPLY_SENT),
            contentIconId = IconId.SEND
        )

        is MessageComposeState.NotSent -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = copy.resolve(ClusterToken.REPLY_NOT_SENT),
            contentIconId = IconId.DFU_WARNING,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = IconId.LIVE_SEND,
            rightActionBgColorId = ColorId.POSITIVE
        )
    }

    private fun truncateToLineBudget(text: String): String {
        val maxBytes = TextBudget.LINE.maxBytes
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text

        var length = maxBytes
        while (length > 0 && (bytes[length].toInt() and 0xC0) == 0x80) {
            length--
        }
        return String(bytes, 0, length, Charsets.UTF_8).trimEnd()
    }

    private fun quickListIcon(): IconId? = IconId.LIVE_REPLY.takeIf { quickReplies.value.isNotEmpty() }

    private fun chooseFrame(requestId: Int, face: MessageComposeState.Choosing): ProjectionFrame {
        val replies = quickReplies.value
        val upIcon = if (face.index > 0) IconId.MENU_ARROW_UP else IconId.IC_NONE
        val downIcon =
            if (face.index < replies.lastIndex) IconId.MENU_ARROW_DOWN else IconId.IC_NONE

        return ProjectionFrame.MenuScreen(
            requestId = requestId,
            headerBgColorId = ColorId.BLUE,
            headerText = copy.resolve(ClusterToken.HEADER_MESSAGES),
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.BLUE_LIGHT,
            upActionIconId = upIcon,
            upActionIconTintColorId = ARROW_TINT.takeIf { upIcon != IconId.IC_NONE },
            downActionIconId = downIcon,
            downActionIconTintColorId = ARROW_TINT.takeIf { downIcon != IconId.IC_NONE },
            leftActionIconId = IconId.MENU_ARROW_LEFT,
            leftActionIconTintColorId = ARROW_TINT,
            rightActionIconId = IconId.MENU_ARROW_RIGHT,
            rightActionIconTintColorId = ARROW_TINT,
            contentIconId = IconId.IC_NONE,
            contentIconTintColorId = null,
            contentTextLine1 = replies.getOrElse(face.index) { "" },
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = "",
            contentTextColorIdLine2 = null
        )
    }

    override fun mirror(): MirrorPage = mirrorPageOf(
        name,
        render(MIRROR_REQUEST_ID),
        actions = movingActions() + verticalActions() + listOf(
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
    )

    private fun verticalActions(): List<MirrorAction> =
        if (_state.value is MessageComposeState.Choosing) {
            listOf(
                MirrorAction(
                    token = MirrorActionToken.CAROUSEL_PREVIOUS,
                    functionId = FunctionId.PREVIOUS,
                    slot = MirrorSlot.UP,
                    aliases = setOf(FunctionId.VOLUME_UP)
                ),
                MirrorAction(
                    token = MirrorActionToken.CAROUSEL_NEXT,
                    functionId = FunctionId.NEXT,
                    slot = MirrorSlot.DOWN,
                    aliases = setOf(FunctionId.VOLUME_DOWN)
                )
            )
        } else {
            listOf(
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
                )
            )
        }

    private fun movingActions(): List<MirrorAction> = when (_state.value) {
        MessageComposeState.Listening ->
            listOf(
                MirrorAction(MirrorActionToken.CANCEL_DICTATION, FunctionId.BACK, MirrorSlot.LEFT)
            ) + quickListAction()

        is MessageComposeState.Confirming -> listOf(
            MirrorAction(MirrorActionToken.RETRY_DICTATION, FunctionId.BACK, MirrorSlot.LEFT),
            MirrorAction(MirrorActionToken.SEND_MESSAGE, FunctionId.SELECT, MirrorSlot.PRIMARY)
        )

        MessageComposeState.NothingUnderstood,
        is MessageComposeState.Failed ->
            listOf(
                MirrorAction(MirrorActionToken.RETRY_DICTATION, FunctionId.BACK, MirrorSlot.LEFT)
            ) + quickListAction()

        is MessageComposeState.Sending, MessageComposeState.Sent -> emptyList()

        is MessageComposeState.Choosing -> listOf(
            MirrorAction(MirrorActionToken.GO_BACK, FunctionId.BACK, MirrorSlot.LEFT),
            MirrorAction(MirrorActionToken.SEND_MESSAGE, FunctionId.SELECT, MirrorSlot.PRIMARY)
        )

        is MessageComposeState.NotSent -> listOf(
            MirrorAction(MirrorActionToken.RETRY_DICTATION, FunctionId.BACK, MirrorSlot.LEFT),
            MirrorAction(MirrorActionToken.SEND_MESSAGE, FunctionId.SELECT, MirrorSlot.PRIMARY)
        )
    }

    private fun quickListAction(): List<MirrorAction> =
        if (quickReplies.value.isEmpty()) {
            emptyList()
        } else {
            listOf(
                MirrorAction(MirrorActionToken.QUICK_REPLIES, FunctionId.SELECT, MirrorSlot.PRIMARY)
            )
        }

    override fun onInput(functionId: FunctionId): NavigationAction = when (functionId) {
        FunctionId.BACK -> onBack()

        FunctionId.MENU -> NavigationAction.Reset

        FunctionId.SELECT -> onSelect()

        FunctionId.PREVIOUS, FunctionId.VOLUME_UP -> moveOrRaise(-1)

        FunctionId.NEXT, FunctionId.VOLUME_DOWN -> moveOrRaise(+1)

        FunctionId.VOLUME_MUTE -> {
            volumeSource?.toggleMute()
            NavigationAction.None
        }

        else -> NavigationAction.None
    }

    private fun moveOrRaise(step: Int): NavigationAction {
        val face = _state.value
        if (face is MessageComposeState.Choosing) {
            val last = quickReplies.value.lastIndex
            val moved = (face.index + step).coerceIn(0, maxOf(last, 0))
            _cues.tryEmit(if (moved == face.index) Cue.EDGE else Cue.MOVE)
            _state.value = face.copy(index = moved)
            return NavigationAction.None
        }
        if (step < 0) volumeSource?.raise() else volumeSource?.lower()
        return NavigationAction.None
    }

    private fun onBack(): NavigationAction = when (val face = _state.value) {
        MessageComposeState.Listening -> NavigationAction.Pop

        is MessageComposeState.Confirming,
        MessageComposeState.NothingUnderstood,
        is MessageComposeState.Failed,
        is MessageComposeState.NotSent -> {
            _state.value = MessageComposeState.Listening
            NavigationAction.None
        }

        is MessageComposeState.Choosing -> {
            _state.value = face.from
            NavigationAction.None
        }

        is MessageComposeState.Sending, MessageComposeState.Sent -> NavigationAction.None
    }

    private fun onSelect(): NavigationAction {
        when (val face = _state.value) {
            is MessageComposeState.Confirming -> _state.value = MessageComposeState.Sending(face.text)

            is MessageComposeState.Choosing ->
                quickReplies.value.getOrNull(face.index)?.let {
                    _state.value = MessageComposeState.Sending(it)
                }

            is MessageComposeState.NotSent -> _state.value = MessageComposeState.Sending(face.text)

            MessageComposeState.Listening,
            MessageComposeState.NothingUnderstood,
            is MessageComposeState.Failed -> openQuickList(face)

            is MessageComposeState.Sending, MessageComposeState.Sent -> Unit
        }
        return NavigationAction.None
    }

    private fun openQuickList(from: MessageComposeState) {
        if (quickReplies.value.isEmpty()) {
            return
        }
        _state.value = MessageComposeState.Choosing(index = 0, from = from)
    }

    private companion object {
        val ARROW_TINT = ColorId.PURE_BLACK
    }
}
