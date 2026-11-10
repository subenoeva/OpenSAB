package com.opensab.cluster.nav

import com.opensab.cluster.CuingPage
import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
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
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.nav.DeviceUnlockSource
import com.opensab.domain.nav.GuidanceAwaiter
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.nav.NavRelaunchPolicy
import com.opensab.domain.nav.NavigationLaunch
import com.opensab.domain.nav.NavigationLauncher
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Voice destination search.
 *
 * - Discarded as soon as an interruption covers it.
 * - [onInput] is synchronous and dispatches no coroutine.
 * - Action icons match the audio cues: retry on the left, accept on the right.
 * - Shows a loading state while waiting for the first turn-by-turn instruction, with a watchdog
 *   for queries the navigation app found ambiguous. The wait outlives the watchdog, and each unlock
 *   of the phone re-sends the route, as [NavRelaunchPolicy] allows.
 * - No error screen traps the rider: BACK always pops to the menu.
 */
internal class NavVoiceSearchPageProvider(
    private val recognizer: SpeechRecognizer,
    private val launcher: NavigationLauncher,
    private val unlockSource: DeviceUnlockSource,
    private val navInstructionFlow: StateFlow<NavInstruction?>,
    private val livePageFactory: () -> PageProvider,
    private val copy: ClusterTextResolver,
    private val volumeSource: VolumeSource? = null,
    private val tbtWatchdogTimeout: Duration = 15.seconds
) : SpeakingPage, WorkingPage, CuingPage {

    override val name: String = "NavVoiceSearchPage"

    private val _cues = cueEmitter()
    override val cues: Flow<Cue> = _cues.asSharedFlow()

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.DISCARD

    private var wasInterrupted: Boolean = false

    private val guidanceAwaiter = GuidanceAwaiter(launcher, unlockSource, navInstructionFlow)

    private val _state = MutableStateFlow<NavVoiceState>(NavVoiceState.Listening)
    val state: StateFlow<NavVoiceState> = _state.asStateFlow()

    override val speech: Flow<List<SpeechRequest>> = _state
        .map { face ->
            when (face) {
                is NavVoiceState.Confirming -> listOf(SpeechRequest(SpeechToken.NAV_CONFIRM, SpeechArg.Text(face.query)))
                NavVoiceState.NothingUnderstood -> listOf(SpeechRequest(SpeechToken.NAV_NOTHING_UNDERSTOOD))
                is NavVoiceState.Failed -> listOf(SpeechRequest(spokenFailureFor(face.reason, SpeechToken.NAV_FAILED)))
                NavVoiceState.NoAppAvailable -> listOf(SpeechRequest(SpeechToken.NAV_NO_APP_AVAILABLE))
                is NavVoiceState.AmbiguousQuery -> listOf(SpeechRequest(SpeechToken.NAV_AMBIGUOUS_QUERY))
                is NavVoiceState.UnlockPhone -> listOf(SpeechRequest(SpeechToken.NAV_UNLOCK_PHONE))
                NavVoiceState.Listening,
                is NavVoiceState.Launching -> emptyList()
            }
        }

    override val invalidations: Flow<Unit> = redrawOn(_state)

    override suspend fun work(): NavigationAction = coroutineScope {
        if (wasInterrupted) {
            Log.d("Voice search interrupted; popping on resume")
            return@coroutineScope NavigationAction.Pop
        }
        val workScope = this
        var finished = false
        try {
            val outcome = CompletableDeferred<NavigationAction>()
            val runner = launch {
                _state.collectLatest { face -> advance(face, outcome, workScope) }
            }
            val action = outcome.await()
            runner.cancel()
            finished = true
            action
        } finally {
            if (!finished) {
                wasInterrupted = true
            }
        }
    }

    private suspend fun advance(
        face: NavVoiceState,
        outcome: CompletableDeferred<NavigationAction>,
        workScope: CoroutineScope
    ) {
        when (face) {
            NavVoiceState.Listening -> listenOnce()
            is NavVoiceState.Launching -> launchApp(face.query, outcome, workScope)
            is NavVoiceState.Confirming,
            NavVoiceState.NothingUnderstood,
            is NavVoiceState.Failed,
            NavVoiceState.NoAppAvailable,
            is NavVoiceState.AmbiguousQuery,
            is NavVoiceState.UnlockPhone -> Unit
        }
    }

    private suspend fun listenOnce() {
        _cues.tryEmit(Cue.LISTEN)
        val heard = try {
            recognizer.listen()
        } catch (e: SpeechRecognitionException) {
            Log.w("Voice recognition failed: %s", e.reason)
            _cues.tryEmit(Cue.FAILURE)
            _state.value = NavVoiceState.Failed(e.reason)
            return
        }
        if (heard.isNullOrBlank()) {
            _cues.tryEmit(Cue.FAILURE)
            _state.value = NavVoiceState.NothingUnderstood
            return
        }
        _state.value = NavVoiceState.Confirming(heard.trim())
    }

    /**
     * Guidance is awaited in [workScope], not under `collectLatest`: the watchdog changes the face,
     * and that must not cancel the wait. It ends with the page's work, so a BACK stops the relaunches.
     */
    private fun launchApp(
        query: String,
        outcome: CompletableDeferred<NavigationAction>,
        workScope: CoroutineScope
    ) {
        val launch = launcher.launchNavigation(query)
        if (launch !is NavigationLaunch.Started) {
            _cues.tryEmit(Cue.FAILURE)
            _state.value = NavVoiceState.NoAppAvailable
            return
        }

        _cues.tryEmit(Cue.SUCCESS)

        val policy = NavRelaunchPolicy(baseline = navInstructionFlow.value)
        workScope.launch {
            val guidance = async { guidanceAwaiter.await(query, policy) }
            if (withTimeoutOrNull(tbtWatchdogTimeout) { guidance.await() } == null) {
                _cues.tryEmit(Cue.FAILURE)
                _state.value = if (launch.deviceLocked) {
                    NavVoiceState.UnlockPhone(query)
                } else {
                    NavVoiceState.AmbiguousQuery(query)
                }
                guidance.await()
            }
            outcome.complete(NavigationAction.Replace(livePageFactory()))
        }
    }

    override fun render(requestId: Int): ProjectionFrame {
        val header = copy.resolve(ClusterToken.HEADER_NAV_SHORT)
        return when (val face = _state.value) {
            NavVoiceState.Listening -> LiveScreenPresets.navVoice(
                requestId = requestId,
                header = header,
                line1 = copy.resolve(ClusterToken.NAV_VOICE_LISTENING),
                line2 = "",
                contentIconId = IconId.MIC,
                leftActionIcon = IconId.LIVE_CANCEL_ONE_BUTTON
            )

            is NavVoiceState.Confirming -> {
                val (l1, l2) = LiveScreenPresets.splitDestinationText(face.query)
                LiveScreenPresets.navVoice(
                    requestId = requestId,
                    header = header,
                    line1 = l1,
                    line2 = l2,
                    contentIconId = IconId.IC_NONE,
                    leftActionIcon = IconId.LIVE_RETRY,
                    rightActionIcon = IconId.LIVE_ACCEPT,
                    rightActionBgColor = ColorId.POSITIVE
                )
            }

            is NavVoiceState.Launching -> LiveScreenPresets.navVoice(
                requestId = requestId,
                header = header,
                line1 = copy.resolve(ClusterToken.NAV_LAUNCHING),
                line2 = "",
                contentIconId = IconId.LOADING
            )

            NavVoiceState.NothingUnderstood -> LiveScreenPresets.navVoice(
                requestId = requestId,
                header = header,
                line1 = copy.resolve(ClusterToken.NAV_VOICE_NOTHING_UNDERSTOOD),
                line2 = "",
                contentIconId = IconId.MIC,
                leftActionIcon = IconId.LIVE_CANCEL_ONE_BUTTON,
                rightActionIcon = IconId.LIVE_RETRY
            )

            is NavVoiceState.Failed -> LiveScreenPresets.navVoice(
                requestId = requestId,
                header = header,
                line1 = copy.resolve(clusterLineFor(face.reason)),
                line2 = "",
                contentIconId = IconId.MIC,
                leftActionIcon = IconId.LIVE_CANCEL_ONE_BUTTON,
                rightActionIcon = IconId.LIVE_RETRY
            )

            NavVoiceState.NoAppAvailable -> LiveScreenPresets.navVoice(
                requestId = requestId,
                header = header,
                line1 = copy.resolve(ClusterToken.NAV_VOICE_NO_APP),
                line2 = "",
                contentIconId = IconId.NAVIGATION,
                leftActionIcon = IconId.LIVE_CANCEL_ONE_BUTTON
            )

            is NavVoiceState.AmbiguousQuery -> LiveScreenPresets.navVoice(
                requestId = requestId,
                header = header,
                line1 = copy.resolve(ClusterToken.NAV_VOICE_AMBIGUOUS),
                line2 = "",
                contentIconId = IconId.NAVIGATION,
                leftActionIcon = IconId.LIVE_CANCEL_ONE_BUTTON
            )

            is NavVoiceState.UnlockPhone -> LiveScreenPresets.navVoice(
                requestId = requestId,
                header = header,
                line1 = copy.resolve(ClusterToken.NAV_VOICE_UNLOCK_PHONE),
                line2 = "",
                contentIconId = IconId.NAVIGATION,
                leftActionIcon = IconId.LIVE_CANCEL_ONE_BUTTON
            )
        }
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

    private fun verticalActions(): List<MirrorAction> = listOf(
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

    private fun movingActions(): List<MirrorAction> = when (_state.value) {
        NavVoiceState.Listening -> listOf(
            MirrorAction(MirrorActionToken.CANCEL_DICTATION, FunctionId.BACK, MirrorSlot.LEFT)
        )

        is NavVoiceState.Confirming -> listOf(
            MirrorAction(MirrorActionToken.RETRY_DICTATION, FunctionId.BACK, MirrorSlot.LEFT),
            MirrorAction(MirrorActionToken.OPEN, FunctionId.SELECT, MirrorSlot.PRIMARY)
        )

        is NavVoiceState.Launching -> listOf(
            MirrorAction(MirrorActionToken.CANCEL_DICTATION, FunctionId.BACK, MirrorSlot.LEFT)
        )

        NavVoiceState.NothingUnderstood,
        is NavVoiceState.Failed -> listOf(
            MirrorAction(MirrorActionToken.GO_BACK, FunctionId.BACK, MirrorSlot.LEFT),
            MirrorAction(MirrorActionToken.RETRY_DICTATION, FunctionId.SELECT, MirrorSlot.PRIMARY)
        )

        NavVoiceState.NoAppAvailable,
        is NavVoiceState.AmbiguousQuery,
        is NavVoiceState.UnlockPhone -> listOf(
            MirrorAction(
                token = MirrorActionToken.GO_BACK,
                functionId = FunctionId.BACK,
                slot = MirrorSlot.LEFT,
                aliases = setOf(FunctionId.SELECT)
            )
        )
    }

    override fun onInput(functionId: FunctionId): NavigationAction = when (functionId) {
        FunctionId.BACK -> when (_state.value) {
            NavVoiceState.Listening,
            NavVoiceState.NothingUnderstood,
            is NavVoiceState.Failed,
            NavVoiceState.NoAppAvailable,
            is NavVoiceState.AmbiguousQuery,
            is NavVoiceState.UnlockPhone,
            is NavVoiceState.Launching -> NavigationAction.Pop

            is NavVoiceState.Confirming -> {
                _state.value = NavVoiceState.Listening
                NavigationAction.None
            }
        }

        FunctionId.SELECT -> when (val face = _state.value) {
            is NavVoiceState.Confirming -> {
                _state.value = NavVoiceState.Launching(face.query)
                NavigationAction.None
            }

            NavVoiceState.NothingUnderstood,
            is NavVoiceState.Failed -> {
                _state.value = NavVoiceState.Listening
                NavigationAction.None
            }

            NavVoiceState.NoAppAvailable,
            is NavVoiceState.AmbiguousQuery,
            is NavVoiceState.UnlockPhone -> NavigationAction.Pop

            NavVoiceState.Listening,
            is NavVoiceState.Launching -> NavigationAction.None
        }

        FunctionId.MENU -> NavigationAction.Reset

        FunctionId.PREVIOUS, FunctionId.VOLUME_UP -> {
            volumeSource?.raise()
            NavigationAction.None
        }

        FunctionId.NEXT, FunctionId.VOLUME_DOWN -> {
            volumeSource?.lower()
            NavigationAction.None
        }

        FunctionId.VOLUME_MUTE -> {
            volumeSource?.toggleMute()
            NavigationAction.None
        }

        else -> NavigationAction.None
    }
}
