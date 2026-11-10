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
import com.opensab.domain.audio.Cue
import com.opensab.domain.message.MessageSource
import com.opensab.domain.music.VolumeSource
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
 * Where the answer is dictated, reviewed and sent: one page with a face per [ReplyState].
 *
 * **Why one page and not eight.** This engine has no asynchronous navigation: a [NavigationAction]
 * comes out of [onInput], which is to say out of a key. "It was understood" and "it was sent" are
 * pressed by nobody, so eight pages would each have to push the next one on its own — and that is a
 * second owner of the stack. `CallPageProvider` is the precedent: `RINGING`, `ACTIVE` and standby
 * from one provider and one flow.
 *
 * The consequence is that the stack sees **one** page, so `@Back` from the confirmation goes back
 * through the state and not through the stack.
 *
 * Input Mapping, per face:
 * - MENU -> on all eight: yield the screen and reset
 * - BACK -> cancel while listening, retry the listen after it, retry the send on `NotSent`, and
 *   leave the quick list for the face it was opened from
 * - SELECT -> send from the confirmation and from a picked quick reply; open the quick list from
 *   the four faces with nothing to send yet
 * - PREVIOUS / NEXT -> the volume on seven faces and the cursor on the quick list, with
 *   VOLUME_UP / VOLUME_DOWN as the second naming of the same control throughout
 * - VOLUME_MUTE -> Mute, on all eight
 */
class MessageReplyPageProvider(
    private val recognizer: SpeechRecognizer,
    private val messageSource: MessageSource,
    /** How long the sent face holds the screen, already clamped to its range by whoever read it. */
    private val sentLinger: StateFlow<Duration>,
    /** No default: one would be a page painting English because the graph forgot. */
    private val copy: ClusterTextResolver,
    /**
     * The fixed replies, already resolved: what the rider wrote, or the four the app ships when he
     * wrote nothing. Resolved outside because they are words that leave the phone and this package
     * cannot see `R`.
     *
     * An empty list closes the whole branch — the button, the key and the mirror zone together.
     */
    private val quickReplies: StateFlow<List<String>> = MutableStateFlow(emptyList()),
    private val volumeSource: VolumeSource? = null
) : SpeakingPage, WorkingPage, CuingPage {

    override val name: String = "MessageReplyPage"

    private val _cues = cueEmitter()

    /**
     * MOVE / EDGE on the quick-list cursor, LISTEN before the microphone opens, SUCCESS / FAILURE
     * on the outcome of a send.
     */
    override val cues: Flow<Cue> = _cues.asSharedFlow()

    /**
     * A dictation is not resumed, it is started again.
     *
     * `DISCARD` and not `RETAIN` because what this page holds is a half-finished sentence and an
     * open microphone, and neither survives being covered: the engine cancels [work] on the way out
     * (see [WorkingPage]), so coming back to a `Confirming` face with no work behind it would be a
     * transcript with no way to send it.
     */
    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.DISCARD

    private val _state = MutableStateFlow<ReplyState>(ReplyState.Listening)

    /** The face the cluster is showing. Owned here; [work] and [onInput] are what move it. */
    val state: StateFlow<ReplyState> = _state.asStateFlow()

    /**
     * The transcript read back, once per confirmation.
     *
     * Built on the state and not on the mount, like the question of `MessagePromptPageProvider`:
     * retrying and being understood a second time is a second confirmation and gets read again,
     * while a redraw is neither.
     */
    override val speech: Flow<List<SpeechRequest>> = _state
        .map { face ->
            when (face) {
                is ReplyState.Confirming -> listOf(SpeechRequest(SpeechToken.MESSAGE_CONFIRM, SpeechArg.Text(face.text)))
                ReplyState.NothingUnderstood -> listOf(SpeechRequest(SpeechToken.VOICE_NOTHING_UNDERSTOOD))
                is ReplyState.Failed -> listOf(SpeechRequest(spokenFailureFor(face.reason, SpeechToken.VOICE_FAILED)))
                is ReplyState.Choosing -> {
                    val reply = quickReplies.value.getOrNull(face.index).orEmpty()
                    listOf(SpeechRequest(SpeechToken.MESSAGE_CHOOSING_REPLY, SpeechArg.Text(reply)))
                }
                ReplyState.Sent -> listOf(SpeechRequest(SpeechToken.MESSAGE_SENT))
                is ReplyState.NotSent -> listOf(SpeechRequest(SpeechToken.REPLY_NOT_SENT))
                ReplyState.Listening,
                is ReplyState.Sending -> emptyList()
            }
        }

    override val invalidations: Flow<Unit> = redrawOn(_state, quickReplies)

    /**
     * The dictation, from the tone to the pop.
     *
     * It is a loop over the state and not a sequence of steps because two of the faces are moved by
     * a key and the rest by what happens: `collectLatest` restarts the handler on every transition,
     * so `@Back` on a `Confirming` face writes `Listening` and the listen begins again from here,
     * with no branch anywhere that says "retry".
     *
     * That same `collectLatest` is what cancels an open microphone when the state moves out of
     * `Listening` for any other reason, which is what makes the quick list reachable mid-listen
     * without a stop of its own.
     *
     * Only [ReplyState.Sent] ends the work, which is why the outcome is a `CompletableDeferred`
     * and not a `return` — a `collectLatest` over a `StateFlow` never completes on its own.
     */
    override suspend fun work(): NavigationAction = coroutineScope {
        val outcome = CompletableDeferred<NavigationAction>()
        val runner = launch {
            _state.collectLatest { face -> advance(face, outcome) }
        }
        val action = outcome.await()
        runner.cancel()
        action
    }

    private suspend fun advance(face: ReplyState, outcome: CompletableDeferred<NavigationAction>) {
        when (face) {
            ReplyState.Listening -> listenOnce()

            is ReplyState.Sending -> send(face.text)

            ReplyState.Sent -> {
                // Read at the instant it is used, so a setting changed mid-ride is the one that
                // applies to the next reply and not to the one after that.
                delay(sentLinger.value)
                Log.d("The reply is away, giving the screen back")
                messageSource.yieldScreen()
                outcome.complete(NavigationAction.Pop)
            }

            // The four faces the rider moves with a key. There is nothing for the work to do but
            // wait for the next state, which `collectLatest` does by suspending here.
            is ReplyState.Confirming,
            ReplyState.NothingUnderstood,
            is ReplyState.Failed,
            is ReplyState.Choosing,
            is ReplyState.NotSent -> Unit
        }
    }

    /**
     * One listen, announced by the tone.
     *
     * The `catch` names [SpeechRecognitionException] and nothing wider on purpose: a
     * `CancellationException` is how leaving the page closes the microphone and it has to reach the
     * job untouched. Silence comes back as null and is not caught at all, because it is not a
     * failure — the rider opened his mouth and nothing came out.
     */
    private suspend fun listenOnce() {
        _cues.tryEmit(Cue.LISTEN)
        val heard = try {
            recognizer.listen()
        } catch (e: SpeechRecognitionException) {
            Log.w("The dictation failed: %s", e.reason)
            _cues.tryEmit(Cue.FAILURE)
            _state.value = ReplyState.Failed(e.reason)
            return
        }
        _state.value = if (heard.isNullOrBlank()) {
            _cues.tryEmit(Cue.FAILURE)
            ReplyState.NothingUnderstood
        } else {
            ReplyState.Confirming(heard)
        }
    }

    private suspend fun send(text: String) {
        val sent = messageSource.reply(text)
        _cues.tryEmit(if (sent) Cue.SUCCESS else Cue.FAILURE)
        _state.value = if (sent) ReplyState.Sent else ReplyState.NotSent(text)
    }

    override fun render(requestId: Int): ProjectionFrame = when (val face = _state.value) {
        ReplyState.Listening -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = copy.resolve(ClusterToken.REPLY_SAY_YOUR_MESSAGE),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_CANCEL_ONE_BUTTON,
            rightActionIcon = quickListIcon()
        )

        is ReplyState.Confirming -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = face.text,
            contentIconId = IconId.REVIEW,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = IconId.LIVE_SEND,
            rightActionBgColorId = ColorId.POSITIVE
        )

        ReplyState.NothingUnderstood -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = copy.resolve(ClusterToken.LISTEN_NOTHING_UNDERSTOOD),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = quickListIcon()
        )

        is ReplyState.Failed -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = copy.resolve(clusterLineFor(face.reason)),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = quickListIcon()
        )

        is ReplyState.Choosing -> chooseFrame(requestId, face)

        // Nothing to press while it is going: pressing "send" twice is how a rider sends the same
        // message twice, and there is no cancel because the intent is already with the app.
        is ReplyState.Sending -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = copy.resolve(ClusterToken.REPLY_SENDING),
            contentIconId = IconId.LOADING
        )

        ReplyState.Sent -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = copy.resolve(ClusterToken.REPLY_SENT),
            contentIconId = IconId.SEND
        )

        is ReplyState.NotSent -> LiveScreenPresets.messageReply(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_MESSAGES),
            line = copy.resolve(ClusterToken.REPLY_NOT_SENT),
            contentIconId = IconId.NO_NETWORK,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = quickListIcon()
        )
    }

    /**
     * The right button of the four faces that offer the list, and nothing when there is no list.
     *
     * One property read by the button, by the `@Select` branch and by the mirror zone, so a rider
     * who emptied his replies in Settings closes all three at once — the shape `canEnter` has on
     * the carousel.
     */
    private fun quickListIcon(): IconId? = IconId.LIVE_REPLY.takeIf { quickReplies.value.isNotEmpty() }

    /**
     * The list itself, and the one `MenuScreen` of this page.
     *
     * A menu and not a live screen because that is what it is — a cursor over entries — and
     * `mirrorPageOf` already twins both kinds, so the choice costs nothing above. The arrows follow
     * `RootMenuPageProvider` byte for byte: `PURE_BLACK` on the arrows, an arrow only where the
     * cursor can actually go, and a tint only where there is an arrow to tint
     * (`FrameEncoder.encodeMenuScreen`).
     */
    private fun chooseFrame(requestId: Int, face: ReplyState.Choosing): ProjectionFrame {
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

    /**
     * The volume block and the escape are on all seven faces; what moves is the left zone and the
     * primary one, and they move with [onInput] and with the frame's buttons together — the three
     * read the same state, so none of them can promise what the other two do not do.
     */
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

    /**
     * The two vertical keys, which are the volume on seven faces and the cursor on the eighth.
     *
     * The aliases move with them: while the list is up, `VOLUME_UP` is the same physical control as
     * `PREVIOUS` and it does not raise anything, exactly as on the carousel — a frame painting a
     * cursor with those two must not also be lending them to the volume.
     */
    private fun verticalActions(): List<MirrorAction> =
        if (_state.value is ReplyState.Choosing) {
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
        ReplyState.Listening ->
            listOf(
                MirrorAction(MirrorActionToken.CANCEL_DICTATION, FunctionId.BACK, MirrorSlot.LEFT)
            ) + quickListAction()

        is ReplyState.Confirming -> listOf(
            MirrorAction(MirrorActionToken.RETRY_DICTATION, FunctionId.BACK, MirrorSlot.LEFT),
            MirrorAction(MirrorActionToken.SEND_MESSAGE, FunctionId.SELECT, MirrorSlot.PRIMARY)
        )

        ReplyState.NothingUnderstood,
        is ReplyState.Failed,
        is ReplyState.NotSent ->
            listOf(
                MirrorAction(MirrorActionToken.RETRY_DICTATION, FunctionId.BACK, MirrorSlot.LEFT)
            ) + quickListAction()

        // A send in flight and a send that landed answer no key of their own, so they declare none:
        // a zone standing for nothing is worse than a zone that is not there.
        is ReplyState.Sending, ReplyState.Sent -> emptyList()

        is ReplyState.Choosing -> listOf(
            MirrorAction(MirrorActionToken.GO_BACK, FunctionId.BACK, MirrorSlot.LEFT),
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

        FunctionId.MENU -> {
            messageSource.yieldScreen()
            NavigationAction.Reset
        }

        FunctionId.SELECT -> onSelect()

        FunctionId.PREVIOUS, FunctionId.VOLUME_UP -> moveOrRaise(-1)

        FunctionId.NEXT, FunctionId.VOLUME_DOWN -> moveOrRaise(+1)

        FunctionId.VOLUME_MUTE -> {
            volumeSource?.toggleMute()
            NavigationAction.None
        }

        else -> NavigationAction.None
    }

    /**
     * The two vertical keys: the cursor while the list is up, the volume everywhere else.
     *
     * The cursor stops at both ends instead of wrapping, like the carousel, and the redraw comes
     * from the state moving rather than from an `Invalidate` returned here — returning both would
     * project the same frame twice, which is the defect
     * `PageProviderInvalidationContractTest` pins.
     */
    private fun moveOrRaise(step: Int): NavigationAction {
        val face = _state.value
        if (face is ReplyState.Choosing) {
            val last = quickReplies.value.lastIndex
            val moved = (face.index + step).coerceIn(0, maxOf(last, 0))
            _cues.tryEmit(if (moved == face.index) Cue.EDGE else Cue.MOVE)
            _state.value = face.copy(index = moved)
            return NavigationAction.None
        }
        // The volume is not a cursor, so nothing is announced here: the EDGE of the volume belongs
        // to VolumePageProvider, which is the page that can read the level.
        if (step < 0) volumeSource?.raise() else volumeSource?.lower()
        return NavigationAction.None
    }

    /**
     * `@Back` is a cancel on the first face and a retry on the rest, and that asymmetry is the
     * frame's doing rather than a special case: what the confirmation paints on the left is
     * `LIVE_RETRY`, so that is what the key has to do. Leaving it is `@Menu`, exactly as on the
     * reading page.
     */
    private fun onBack(): NavigationAction = when (val face = _state.value) {
        ReplyState.Listening -> {
            Log.d("Dictation cancelled, the message stays unread")
            messageSource.yieldScreen()
            NavigationAction.Pop
        }

        is ReplyState.NotSent -> {
            _state.value = ReplyState.Sending(face.text)
            NavigationAction.None
        }

        is ReplyState.Confirming, ReplyState.NothingUnderstood, is ReplyState.Failed -> {
            _state.value = ReplyState.Listening
            NavigationAction.None
        }

        is ReplyState.Choosing -> {
            _state.value = face.from
            NavigationAction.None
        }

        is ReplyState.Sending, ReplyState.Sent -> NavigationAction.None
    }

    /**
     * Two ways to send and one way to open the list, and both ways to send go through
     * [ReplyState.Sending]: there is a single path out of this page to the other phone, and it is
     * the one `work()` already walks.
     *
     * Opening the list does **not** send. It takes a second press, on an entry already under the
     * cursor, for anything to leave — one botched press must not put words on somebody else's
     * phone, which is the rule the carousel follows for the same reason.
     */
    private fun onSelect(): NavigationAction {
        when (val face = _state.value) {
            is ReplyState.Confirming -> _state.value = ReplyState.Sending(face.text)

            is ReplyState.Choosing ->
                quickReplies.value.getOrNull(face.index)?.let {
                    _state.value = ReplyState.Sending(it)
                }

            // The three faces with nothing to hear, plus the listen itself: `@Select` is free on
            // all four, and the list is what it is for — "cannot speak" and "would rather not" are
            // the two halves of the same door.
            ReplyState.Listening, ReplyState.NothingUnderstood,
            is ReplyState.Failed, is ReplyState.NotSent -> openQuickList(face)

            is ReplyState.Sending, ReplyState.Sent -> Unit
        }
        return NavigationAction.None
    }

    private fun openQuickList(from: ReplyState) {
        if (quickReplies.value.isEmpty()) {
            Log.d("No quick replies to offer, the press does nothing")
            return
        }
        _state.value = ReplyState.Choosing(index = 0, from = from)
    }

    private companion object {
        val ARROW_TINT = ColorId.PURE_BLACK
    }
}
