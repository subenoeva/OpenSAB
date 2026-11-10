package com.opensab.cluster.message

import com.opensab.cluster.CuingPage
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.WorkingPage
import com.opensab.cluster.call.DialSeed
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
import com.opensab.domain.contacts.CallTarget
import com.opensab.domain.contacts.ContactDirectory
import com.opensab.domain.contacts.ContactMatcher
import com.opensab.domain.contacts.PhoneKind
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map

/**
 * The faces of [MessageContactSelectPageProvider], corresponding to the contact search
 * and disambiguation lifecycle before moving to compose.
 */
internal sealed interface MessageContactSelectState {

    /** The microphone is open listening for a recipient name. */
    data object Listening : MessageContactSelectState

    /**
     * The candidates matching what was heard or seeded from favorites, with the cursor on [index].
     * Pressing SELECT confirms the candidate and moves to composition.
     */
    data class Confirming(val targets: List<CallTarget>, val index: Int) : MessageContactSelectState

    /** The microphone opened and nothing was understood. */
    data object NothingUnderstood : MessageContactSelectState

    /** A name was understood but the address book has no matching contact. */
    data class NoMatch(val heard: String) : MessageContactSelectState

    /** Speech recognition failed with an actionable error. */
    data class Failed(val reason: RecognitionFailure) : MessageContactSelectState
}

/**
 * Where a message recipient is dictated or picked from favorites.
 *
 * `BACK` cancels while listening; on confirmation it listens again (voice seed) or leaves
 * (favorites seed); from the three dead ends it listens again.
 */
internal class MessageContactSelectPageProvider(
    seed: DialSeed,
    private val recognizer: SpeechRecognizer,
    private val directory: ContactDirectory,
    private val composePageFactory: (CallTarget) -> PageProvider,
    private val copy: ClusterTextResolver,
    private val volumeSource: VolumeSource? = null
) : SpeakingPage, WorkingPage, CuingPage {

    init {
        require(seed !is DialSeed.Favorites || seed.targets.isNotEmpty()) {
            "the favourites row is shut when there are none, so this seed cannot be empty"
        }
    }

    override val name: String = "MessageContactSelectPage"

    private val _cues = cueEmitter()

    override val cues: Flow<Cue> = _cues.asSharedFlow()

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.DISCARD

    private val listensAgainOnBack: Boolean = seed is DialSeed.Voice

    private val _state = MutableStateFlow(
        when (seed) {
            DialSeed.Voice -> MessageContactSelectState.Listening
            is DialSeed.Favorites -> MessageContactSelectState.Confirming(seed.targets, 0)
        }
    )

    val state: StateFlow<MessageContactSelectState> = _state.asStateFlow()

    override val speech: Flow<List<SpeechRequest>> = _state
        .map { face ->
            when (face) {
                is MessageContactSelectState.Confirming -> {
                    val name = face.targets.getOrNull(face.index)?.displayName.orEmpty()
                    listOf(SpeechRequest(SpeechToken.CALL_CONFIRM, SpeechArg.Text(name)))
                }
                MessageContactSelectState.NothingUnderstood -> listOf(SpeechRequest(SpeechToken.VOICE_NOTHING_UNDERSTOOD))
                is MessageContactSelectState.Failed -> listOf(SpeechRequest(spokenFailureFor(face.reason, SpeechToken.VOICE_FAILED)))
                is MessageContactSelectState.NoMatch -> listOf(SpeechRequest(SpeechToken.VOICE_NO_MATCH, SpeechArg.Text(face.heard)))
                MessageContactSelectState.Listening -> emptyList()
            }
        }

    override val invalidations: Flow<Unit> = redrawOn(_state)

    override suspend fun work(): NavigationAction {
        _state.collectLatest { face ->
            if (face is MessageContactSelectState.Listening) {
                listenOnce()
            }
        }
    }

    private suspend fun listenOnce() {
        _cues.tryEmit(Cue.LISTEN)
        val heard = try {
            recognizer.listen()
        } catch (e: SpeechRecognitionException) {
            Log.w("The listen failed: %s", e.reason)
            _cues.tryEmit(Cue.FAILURE)
            _state.value = MessageContactSelectState.Failed(e.reason)
            return
        }
        if (heard.isNullOrBlank()) {
            _cues.tryEmit(Cue.FAILURE)
            _state.value = MessageContactSelectState.NothingUnderstood
            return
        }
        val targets = ContactMatcher.rank(heard, directory.search(ContactMatcher.queryFor(heard)))
        _state.value = if (targets.isEmpty()) {
            Log.d("Nobody in the address book matches what was heard")
            _cues.tryEmit(Cue.FAILURE)
            MessageContactSelectState.NoMatch(heard)
        } else {
            MessageContactSelectState.Confirming(targets, 0)
        }
    }

    override fun render(requestId: Int): ProjectionFrame = when (val face = _state.value) {
        MessageContactSelectState.Listening -> renderFrame(
            requestId = requestId,
            line1 = copy.resolve(ClusterToken.DIAL_SAY_A_NAME),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_CANCEL_ONE_BUTTON
        )

        is MessageContactSelectState.Confirming -> {
            val target = face.targets.getOrNull(face.index)
            renderFrame(
                requestId = requestId,
                line1 = target?.displayName.orEmpty(),
                line2 = target?.let { copy.resolve(labelFor(it.kind)) }.orEmpty(),
                leftActionIcon =
                    if (listensAgainOnBack) IconId.LIVE_RETRY else IconId.LIVE_CANCEL_ONE_BUTTON,
                rightActionIcon = IconId.LIVE_REPLY,
                rightActionBgColorId = ColorId.POSITIVE
            )
        }

        MessageContactSelectState.NothingUnderstood -> renderFrame(
            requestId = requestId,
            line1 = copy.resolve(ClusterToken.LISTEN_NOTHING_UNDERSTOOD),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY
        )

        is MessageContactSelectState.NoMatch -> renderFrame(
            requestId = requestId,
            line1 = copy.resolve(ClusterToken.DIAL_NOT_IN_CONTACTS),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY
        )

        is MessageContactSelectState.Failed -> renderFrame(
            requestId = requestId,
            line1 = copy.resolve(clusterLineFor(face.reason)),
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY
        )
    }

    private fun renderFrame(
        requestId: Int,
        line1: String,
        line2: String = "",
        contentIconId: IconId = IconId.IC_NONE,
        leftActionIcon: IconId? = null,
        rightActionIcon: IconId? = null,
        rightActionBgColorId: ColorId = ColorId.BLUE_LIGHT
    ): ProjectionFrame.LiveScreen {
        val contentIcon = if (line2.isNotEmpty()) IconId.IC_NONE else contentIconId
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.BLUE,
            headerText = copy.resolve(ClusterToken.HEADER_MESSAGES),
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.BLUE,
            leftActionIconId =
                if (leftActionIcon != null) IconId.LIVE_ARROW_LEFT else IconId.IC_NONE,
            leftActionIconTintColorId = ColorId.PURE_BLACK.takeIf { leftActionIcon != null },
            leftActionContentIconId = leftActionIcon,
            leftActionContentIconTintColorId = ColorId.PURE_WHITE.takeIf { leftActionIcon != null },
            leftActionBgColorId = ColorId.BLUE_LIGHT.takeIf { leftActionIcon != null },
            rightActionIconId =
                if (rightActionIcon != null) IconId.LIVE_ARROW_RIGHT else IconId.IC_NONE,
            rightActionIconTintColorId = ColorId.PURE_BLACK.takeIf { rightActionIcon != null },
            rightActionContentIconId = rightActionIcon,
            rightActionContentIconTintColorId =
                ColorId.PURE_WHITE.takeIf { rightActionIcon != null },
            rightActionBgColorId = rightActionBgColorId.takeIf { rightActionIcon != null },
            contentBgColorId = ColorId.BLUE_DARK,
            contentIconId = contentIcon,
            contentIconTintColorId = ColorId.PURE_WHITE.takeIf { contentIcon != IconId.IC_NONE },
            contentTextLine1 = line1,
            contentTextColorIdLine1 = ColorId.PURE_WHITE.takeIf { line1.isNotEmpty() },
            contentTextLine2 = line2,
            contentTextColorIdLine2 = ColorId.PURE_WHITE.takeIf { line2.isNotEmpty() }
        )
    }

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

    private fun verticalActions(): List<MirrorAction> =
        if (_state.value is MessageContactSelectState.Confirming) {
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
        MessageContactSelectState.Listening -> listOf(
            MirrorAction(MirrorActionToken.CANCEL_DICTATION, FunctionId.BACK, MirrorSlot.LEFT)
        )

        is MessageContactSelectState.Confirming -> listOf(
            MirrorAction(
                token = if (listensAgainOnBack) {
                    MirrorActionToken.RETRY_DICTATION
                } else {
                    MirrorActionToken.GO_BACK
                },
                functionId = FunctionId.BACK,
                slot = MirrorSlot.LEFT
            ),
            MirrorAction(MirrorActionToken.OPEN, FunctionId.SELECT, MirrorSlot.PRIMARY)
        )

        MessageContactSelectState.NothingUnderstood,
        is MessageContactSelectState.NoMatch,
        is MessageContactSelectState.Failed -> listOf(
            MirrorAction(MirrorActionToken.RETRY_DICTATION, FunctionId.BACK, MirrorSlot.LEFT)
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
        if (face is MessageContactSelectState.Confirming) {
            val last = face.targets.lastIndex
            val moved = (face.index + step).coerceIn(0, maxOf(last, 0))
            _cues.tryEmit(if (moved == face.index) Cue.EDGE else Cue.MOVE)
            _state.value = face.copy(index = moved)
            return NavigationAction.None
        }
        if (step < 0) volumeSource?.raise() else volumeSource?.lower()
        return NavigationAction.None
    }

    private fun onBack(): NavigationAction = when (_state.value) {
        MessageContactSelectState.Listening -> {
            Log.d("Message contact selection cancelled before anything was said")
            NavigationAction.Pop
        }

        is MessageContactSelectState.Confirming ->
            if (listensAgainOnBack) {
                _state.value = MessageContactSelectState.Listening
                NavigationAction.None
            } else {
                NavigationAction.Pop
            }

        MessageContactSelectState.NothingUnderstood,
        is MessageContactSelectState.NoMatch,
        is MessageContactSelectState.Failed -> {
            _state.value = MessageContactSelectState.Listening
            NavigationAction.None
        }
    }

    private fun onSelect(): NavigationAction {
        val face = _state.value
        if (face is MessageContactSelectState.Confirming) {
            val target = face.targets.getOrNull(face.index)
            if (target != null) {
                return NavigationAction.Replace(composePageFactory(target))
            }
        }
        return NavigationAction.None
    }
}
