package com.opensab.cluster.call

import com.opensab.cluster.CuingPage
import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.WorkingPage
import com.opensab.cluster.clusterLineFor
import com.opensab.cluster.cueEmitter
import com.opensab.cluster.message.MessageReplyPageProvider
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
import com.opensab.domain.call.CallDialer
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallStatus
import com.opensab.domain.contacts.CallTarget
import com.opensab.domain.contacts.ContactDirectory
import com.opensab.domain.contacts.ContactMatcher
import com.opensab.domain.contacts.PhoneKind
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Where a call is dictated, chosen and placed. Twin of [MessageReplyPageProvider], face for face.
 *
 * One provider and a [DialState] rather than a page per face: a [NavigationAction] comes out of
 * [onInput], which is to say out of a key, and "it was understood" and "it went through" are
 * pressed by nobody. Eight pages would each have to push the next one on their own, which is a
 * second owner of the stack.
 *
 * **The seed changes exactly one thing**: what `@Back` does on the confirmation — listen again when
 * it came from the voice, leave when it came from the favourites. Render, keys, mirror and voice are
 * the same code, which is why the favourites are not a page of their own: the confirmation face
 * already **is** a list of contacts.
 *
 * Input Mapping, per face:
 * - MENU -> `Reset` on all seven
 * - BACK -> cancel while listening; on the confirmation, listen again (voice) or leave (favourites);
 *   listen again from the three dead ends; retry the call from `NotPlaced`; nothing while placing
 * - SELECT -> place the call of the target under the cursor, and nothing anywhere else
 * - PREVIOUS / NEXT -> the cursor on the confirmation and the volume on the other six, with
 *   VOLUME_UP / VOLUME_DOWN as the second naming of the same control throughout
 * - VOLUME_MUTE -> Mute, on all seven
 */
class CallDialPageProvider(
    seed: DialSeed,
    private val recognizer: SpeechRecognizer,
    private val directory: ContactDirectory,
    private val dialer: CallDialer,
    /**
     * The live call page of the session — one instance, shared with the interrupt stack, because
     * that identity is what holds `RINGING -> ACTIVE` together.
     */
    private val livePageFactory: () -> PageProvider,
    /** No default: one would be a page painting English because the graph forgot. */
    private val copy: ClusterTextResolver,
    private val volumeSource: VolumeSource? = null,
    /** The session's call, watched only to hand over once the placed call is live. */
    private val activeCall: StateFlow<CallInfo?>
) : SpeakingPage, WorkingPage, CuingPage {

    init {
        require(seed !is DialSeed.Favorites || seed.targets.isNotEmpty()) {
            "the favourites row is shut when there are none, so this seed cannot be empty"
        }
    }

    override val name: String = "CallDialPage"

    private val _cues = cueEmitter()

    /**
     * MOVE / EDGE on the confirmation cursor, LISTEN before the microphone opens, SUCCESS / FAILURE
     * on the outcome of a call.
     */
    override val cues: Flow<Cue> = _cues.asSharedFlow()

    /**
     * Half-dialled is not resumed, it is started again — the same as the dictation of a reply. What
     * this page holds is an open microphone and a half-formed intention, and neither survives being
     * covered: the engine cancels [work] on the way out (see [WorkingPage]).
     */
    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.DISCARD

    /** Whether `@Back` on the confirmation listens again or leaves. The only thing the seed moves. */
    private val listensAgainOnBack: Boolean = seed is DialSeed.Voice

    private val _state = MutableStateFlow(
        when (seed) {
            DialSeed.Voice -> DialState.Listening
            is DialSeed.Favorites -> DialState.Confirming(seed.targets, 0)
        }
    )

    /** The face the cluster is showing. Owned here; [work] and [onInput] are what move it. */
    val state: StateFlow<DialState> = _state.asStateFlow()

    /**
     * The name under the cursor, read out.
     *
     * Built on the state and not on the mount, like `MESSAGE_CONFIRM`: **moving the cursor reads the
     * new name**, which is the only thing that makes a list usable with a helmet on, and a redraw
     * reads nothing.
     */
    override val speech: Flow<List<SpeechRequest>> = _state
        .map { face ->
            when (face) {
                is DialState.Confirming -> {
                    val name = face.targets.getOrNull(face.index)?.displayName.orEmpty()
                    listOf(SpeechRequest(SpeechToken.CALL_CONFIRM, SpeechArg.Text(name)))
                }
                DialState.NothingUnderstood -> listOf(SpeechRequest(SpeechToken.VOICE_NOTHING_UNDERSTOOD))
                is DialState.Failed -> listOf(SpeechRequest(spokenFailureFor(face.reason, SpeechToken.VOICE_FAILED)))
                is DialState.NoMatch -> listOf(SpeechRequest(SpeechToken.VOICE_NO_MATCH, SpeechArg.Text(face.heard)))
                is DialState.NotPlaced -> listOf(SpeechRequest(SpeechToken.CALL_NOT_PLACED, SpeechArg.Text(face.target.displayName)))
                DialState.Listening,
                is DialState.Placing -> emptyList()
            }
        }

    override val invalidations: Flow<Unit> = redrawOn(_state)

    /**
     * From the tone to the live call.
     *
     * A loop over the state and not a sequence of steps, exactly like the reply page: `collectLatest`
     * restarts the handler on every transition, so `@Back` on a confirmation writes `Listening` and
     * the listen begins again from here, with no branch anywhere that says "retry" — and an open
     * microphone is cancelled by the state moving for any other reason.
     *
     * Only a placed call ends the work, which is why the outcome is a `CompletableDeferred` and not
     * a `return`: a `collectLatest` over a `StateFlow` never completes on its own. The action it
     * carries is applied by another job (see [WorkingPage]).
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

    private suspend fun advance(face: DialState, outcome: CompletableDeferred<NavigationAction>) {
        when (face) {
            DialState.Listening -> listenOnce()

            is DialState.Placing -> place(face.target, outcome)

            // The four faces the rider moves with a key. There is nothing for the work to do but
            // wait for the next state, which `collectLatest` does by suspending here.
            is DialState.Confirming,
            DialState.NothingUnderstood,
            is DialState.NoMatch,
            is DialState.Failed,
            is DialState.NotPlaced -> Unit
        }
    }

    /**
     * One listen, announced by the tone, and the ranking of what came back.
     *
     * The `catch` names [SpeechRecognitionException] and nothing wider on purpose: a
     * `CancellationException` is how leaving the page closes the microphone and it has to reach the
     * job untouched. Silence comes back as null and is not caught at all, because it
     * is not a failure.
     *
     * The platform lookup gets the query with the courtesy verb already off it, and the ranking gets
     * what was heard: [ContactMatcher.queryFor] is idempotent, so passing it either way lands on the
     * same string.
     */
    private suspend fun listenOnce() {
        _cues.tryEmit(Cue.LISTEN)
        val heard = try {
            recognizer.listen()
        } catch (e: SpeechRecognitionException) {
            Log.w("The listen failed: %s", e.reason)
            _cues.tryEmit(Cue.FAILURE)
            _state.value = DialState.Failed(e.reason)
            return
        }
        if (heard.isNullOrBlank()) {
            _cues.tryEmit(Cue.FAILURE)
            _state.value = DialState.NothingUnderstood
            return
        }
        val targets = ContactMatcher.rank(heard, directory.search(ContactMatcher.queryFor(heard)))
        _state.value = if (targets.isEmpty()) {
            Log.d("Nobody in the address book matches what was heard")
            _cues.tryEmit(Cue.FAILURE)
            DialState.NoMatch(heard)
        } else {
            DialState.Confirming(targets, 0)
        }
    }

    private suspend fun place(target: CallTarget, outcome: CompletableDeferred<NavigationAction>) {
        if (dialer.place(target.number)) {
            // Before the Replace, so the tone belongs to this page and not to the one arriving.
            _cues.tryEmit(Cue.SUCCESS)
            // The dialler reports the call about a second after the intent; until then the live
            // page has no call and would paint "No calls", so this page keeps saying "Calling...".
            val live = withTimeoutOrNull(CALL_SHOWS_UP_TIMEOUT) {
                activeCall.first { it?.status == CallStatus.RINGING || it?.status == CallStatus.ACTIVE }
            }
            if (live == null) {
                Log.d("The call is away but the dialler has not reported it, handing over anyway")
            } else {
                Log.d("The call is live, handing the screen to the live call page")
            }
            outcome.complete(NavigationAction.Replace(livePageFactory()))
        } else {
            _cues.tryEmit(Cue.FAILURE)
            _state.value = DialState.NotPlaced(target)
        }
    }

    override fun render(requestId: Int): ProjectionFrame = when (val face = _state.value) {
        DialState.Listening -> LiveScreenPresets.callDial(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_PHONE),
            line1 = copy.resolve(ClusterToken.DIAL_SAY_A_NAME),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_CANCEL_ONE_BUTTON
        )

        is DialState.Confirming -> {
            val target = face.targets.getOrNull(face.index)
            LiveScreenPresets.callDial(
                requestId = requestId,
                header = copy.resolve(ClusterToken.HEADER_PHONE),
                line1 = target?.displayName.orEmpty(),
                line2 = target?.let { copy.resolve(labelFor(it.kind)) }.orEmpty(),
                leftActionIcon =
                    if (listensAgainOnBack) IconId.LIVE_RETRY else IconId.LIVE_CANCEL_ONE_BUTTON,
                rightActionIcon = IconId.LIVE_CALL,
                rightActionBgColorId = ColorId.POSITIVE
            )
        }

        DialState.NothingUnderstood -> LiveScreenPresets.callDial(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_PHONE),
            line1 = copy.resolve(ClusterToken.LISTEN_NOTHING_UNDERSTOOD),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY
        )

        is DialState.NoMatch -> LiveScreenPresets.callDial(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_PHONE),
            line1 = copy.resolve(ClusterToken.DIAL_NOT_IN_CONTACTS),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY
        )

        is DialState.Failed -> LiveScreenPresets.callDial(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_PHONE),
            line1 = copy.resolve(clusterLineFor(face.reason)),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY
        )

        is DialState.Placing -> LiveScreenPresets.callDial(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_PHONE),
            line1 = copy.resolve(ClusterToken.CALL_CALLING),
            contentIconId = IconId.LOADING
        )

        // Not NO_NETWORK, which is what the reply page shows when a message does not go: a call that
        // does not go is hardly ever the network, and an icon that lies about the cause sends the
        // rider to look at his coverage when what is missing is a permission.
        is DialState.NotPlaced -> LiveScreenPresets.callDial(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_PHONE),
            line1 = copy.resolve(ClusterToken.CALL_FAILED),
            contentIconId = IconId.CANCEL,
            leftActionIcon = IconId.LIVE_RETRY
        )
    }

    /** A token like every other line of this page: the cluster is localized. */
    private fun labelFor(kind: PhoneKind): ClusterToken = when (kind) {
        PhoneKind.MOBILE -> ClusterToken.PHONE_KIND_MOBILE
        PhoneKind.HOME -> ClusterToken.PHONE_KIND_HOME
        PhoneKind.WORK -> ClusterToken.PHONE_KIND_WORK
        PhoneKind.MAIN -> ClusterToken.PHONE_KIND_MAIN
        PhoneKind.OTHER -> ClusterToken.PHONE_KIND_OTHER
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

    /**
     * The two vertical keys: the cursor on the confirmation, the volume on the other six.
     *
     * The aliases move with them. While the list is up, `VOLUME_UP` is the same physical control as
     * `PREVIOUS` and it raises nothing — a face painting a cursor with those two must not also be
     * lending them to the volume, and a face answering only one of the two names would work on one
     * handlebar and not on the next.
     */
    private fun verticalActions(): List<MirrorAction> =
        if (_state.value is DialState.Confirming) {
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
        DialState.Listening -> listOf(
            MirrorAction(MirrorActionToken.CANCEL_DICTATION, FunctionId.BACK, MirrorSlot.LEFT)
        )

        is DialState.Confirming -> listOf(
            MirrorAction(
                token = if (listensAgainOnBack) {
                    MirrorActionToken.RETRY_DICTATION
                } else {
                    MirrorActionToken.GO_BACK
                },
                functionId = FunctionId.BACK,
                slot = MirrorSlot.LEFT
            ),
            MirrorAction(MirrorActionToken.PLACE_CALL, FunctionId.SELECT, MirrorSlot.PRIMARY)
        )

        DialState.NothingUnderstood,
        is DialState.NoMatch,
        is DialState.Failed -> listOf(
            MirrorAction(MirrorActionToken.RETRY_DICTATION, FunctionId.BACK, MirrorSlot.LEFT)
        )

        is DialState.NotPlaced -> listOf(
            MirrorAction(MirrorActionToken.RETRY_CALL, FunctionId.BACK, MirrorSlot.LEFT)
        )

        // A call on its way answers no key of its own, so it declares none: a zone standing for
        // nothing is worse than a zone that is not there.
        is DialState.Placing -> emptyList()
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

    /**
     * The cursor while there is a list, the volume everywhere else.
     *
     * It stops at both ends instead of wrapping, like the carousel, and the redraw comes from the
     * state moving rather than from an `Invalidate` returned here — returning both would project the
     * same frame twice (`PageProviderInvalidationContractTest`).
     */
    private fun moveOrRaise(step: Int): NavigationAction {
        val face = _state.value
        if (face is DialState.Confirming) {
            val last = face.targets.lastIndex
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
     * `@Back` is a cancel on the first face, a retry on the dead ends, a second attempt on a call
     * that did not go, and on the confirmation it is whichever of those the seed asked for. The
     * asymmetry is the frame's doing rather than a special case: what the face paints on the left is
     * what the key has to do.
     *
     * Leaving from the listen is a `Pop`, which lands on the **two-row menu** and not on the
     * carousel: the stack is carousel -> menu -> dialler and each `Pop` climbs one step. Getting out
     * altogether is two presses, or one of `MENU`.
     */
    private fun onBack(): NavigationAction = when (val face = _state.value) {
        DialState.Listening -> {
            Log.d("Dialling cancelled before anything was said")
            NavigationAction.Pop
        }

        is DialState.Confirming ->
            if (listensAgainOnBack) {
                _state.value = DialState.Listening
                NavigationAction.None
            } else {
                NavigationAction.Pop
            }

        DialState.NothingUnderstood, is DialState.NoMatch, is DialState.Failed -> {
            _state.value = DialState.Listening
            NavigationAction.None
        }

        is DialState.NotPlaced -> {
            _state.value = DialState.Placing(face.target)
            NavigationAction.None
        }

        is DialState.Placing -> NavigationAction.None
    }

    /**
     * One press places one call, and only from the confirmation.
     *
     * Every other face returns `None` without touching the dialler: there is one path out of this
     * page to somebody else's phone, and it goes through [DialState.Placing], which is the one the
     * work already walks.
     */
    private fun onSelect(): NavigationAction {
        val face = _state.value
        if (face is DialState.Confirming) {
            face.targets.getOrNull(face.index)?.let { _state.value = DialState.Placing(it) }
        }
        return NavigationAction.None
    }

    private companion object {
        /** Measured at 1 s on the 2026-09-30 ride; the margin only bounds a dialler that never reports. */
        val CALL_SHOWS_UP_TIMEOUT = 5.seconds
    }
}
