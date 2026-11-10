package com.opensab.cluster

import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.cluster.mirror.mirrorDeliveryOf
import com.opensab.domain.audio.Cue
import com.opensab.domain.audio.CuePlayer
import com.opensab.domain.audio.NoCuePlayer
import com.opensab.logging.Log
import com.opensab.sab.protocol.input.HandlebarEvent
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.input.InputDispatcher
import com.opensab.sab.session.input.ScreenGrant
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.ScreenProjector
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main loop of the projection engine: it mounts the root page when the cluster hands over the
 * screen, dispatches handlebar presses to the active page and writes the resulting frame.
 *
 * While the cluster is in `HU_MODE` the native tachometer owns the screen, so emission is
 * suspended and the current frame is regenerated as soon as `SAB_MODE` comes back.
 *
 * The engine reads [ScreenGrant], not the mode, and behaves on [ScreenGrant.ASSUMED] exactly as on
 * [ScreenGrant.CONFIRMED] with two differences: the mirror is told the handover is unconfirmed, and
 * the confirmation that may follow re-projects the mounted page with the projector's memory wiped,
 * because the frames written before it may never have been painted.
 */
class BaseStateManager(
    private val screenProjector: ScreenProjector,
    private val requestIdGenerator: RequestIdGenerator,
    private val rootPageFactory: () -> PageProvider,
    capabilities: VehicleCapabilitiesHolder,
    private val activeIndicators: ActiveIndicatorsPublisher,
    private val projectionMirror: ProjectionMirror,
    private val interruptSource: InterruptSource = NoInterruptions,
    /**
     * The voice of whatever page is mounted, and the reason this class owns [speechJob].
     *
     * Nullable and defaulted for the same reason [interruptSource] is: an engine assembled in a
     * test that never speaks should not have to declare a voice it does not use. Nothing is hidden
     * in production — `OrchestratorModule` passes it explicitly, and the graph fails to build if
     * `SpeechModule` stops providing it.
     */
    private val pageVoice: PageVoice? = null,
    /**
     * The cues of the session, and the single owner of the player.
     *
     * Nullable-free and defaulted like [interruptSource]: an engine assembled in a test that does
     * not listen should not have to declare a player. Nothing is hidden in production —
     * `OrchestratorModule` passes it explicitly, and it arrives already wrapped in the decorator
     * that reads the setting, so nothing here knows the setting exists.
     */
    private val cuePlayer: CuePlayer = NoCuePlayer,
    popUpQueue: PopUpQueue = PopUpQueue()
) {

    val stack = NavigationStack<PageProvider>()

    /** Modal layer sitting above the page stack; restores the retained page when the queue drains. */
    val popupManager = PopupManager(
        screenProjector = screenProjector,
        onRestoreBase = { projectActivePage() },
        capabilities = capabilities,
        activeIndicators = activeIndicators,
        projectionMirror = projectionMirror,
        queue = popUpQueue
    )

    @Volatile
    private var grant: ScreenGrant = ScreenGrant.NONE

    /** Whether a frame may have been written on an assumed grant and not yet re-projected. */
    private var projectedOnAssumedGrant = false

    private var modeJob: Job? = null
    private var eventJob: Job? = null
    private var indicatorJob: Job? = null
    private var interruptJob: Job? = null
    private var redrawJob: Job? = null

    /**
     * The voice of the mounted page, and the single owner of it.
     *
     * Sibling of [redrawJob] and with exactly the same life: it is launched when the mounted page
     * changes identity and cancelled in the same three places — on the next page change, on
     * [stop], and with the session scope. That is what makes BACK and MENU cut an utterance
     * without a single line here that talks about cutting: both move the stack, the stack changes
     * the mounted page, this job dies and `invokeOnCancellation` stops the engine.
     */
    private var speechJob: Job? = null

    /**
     * The work of the mounted page, and the single owner of it.
     *
     * Sibling of [speechJob] with the same three deaths, and one thing of its own: what it produces
     * is a [NavigationAction], and applying that action here would cancel this job from inside
     * itself. It goes out through [pageActions] instead, to be applied by [actionJob].
     */
    private var workJob: Job? = null

    /**
     * The cues of the mounted page, and the third sibling of [speechJob] and [workJob] with the
     * same three deaths: the next page change, [stop], and the session scope.
     */
    private var cueJob: Job? = null

    /**
     * Where a page's own work asks for navigation, and the reason it is a channel.
     *
     * `RENDEZVOUS` and not a buffer: a mounted page produces one action, at the end of its work,
     * and a queue of them would mean a page navigating twice from a mount that is already over.
     */
    private val pageActions = Channel<NavigationAction>(Channel.RENDEZVOUS)

    /** Applies what [workJob] asks for, from outside the job that asked. Sibling of [eventJob]. */
    private var actionJob: Job? = null

    /** Scope of the connected session, needed to follow the mounted page's own ticks. */
    private var scope: CoroutineScope? = null

    /** The page the redraw collector is currently attached to. Identity, not equality. */
    private var renderedPage: PageProvider? = null

    /**
     * The page currently holding the interrupt stack, tracked here rather than read back off the
     * stack: a `Reset` from the page itself empties the interrupt stack without the source knowing,
     * and then `hasInterrupt` would disagree with what the source thinks it has claimed.
     */
    private var interruptingPage: PageProvider? = null

    fun start(inputDispatcher: InputDispatcher, scope: CoroutineScope) {
        stop()
        this.scope = scope
        Log.i("Starting projection engine loop")
        popupManager.start(scope)

        modeJob = scope.launch {
            inputDispatcher.screenGrant.collect { grant -> onGrantChanged(grant) }
        }

        eventJob = scope.launch {
            inputDispatcher.events.collect { event ->
                if (event is HandlebarEvent.ButtonPressed) {
                    onButtonPressed(event.functionId)
                }
            }
        }

        // The status bar has a single owner and no route of its own to the mirror. This is the one
        // place in the engine that already has a scope, a lifecycle and a symmetric stop().
        indicatorJob = scope.launch {
            activeIndicators.state.collect { projectionMirror.publishIndicators(it) }
        }

        // An incoming call reaches the rider as a page, not as a layer over one, so it arrives here
        // and not through a route of its own.
        interruptJob = scope.launch {
            interruptSource.interrupting.collect { onInterruptChanged(it) }
        }

        // The navigation a page's own work asks for, applied from the session and never from the
        // job that produced it: applying it cancels that job (see [workJob]).
        actionJob = scope.launch {
            for (action in pageActions) {
                apply(action)
            }
        }
    }

    fun stop() {
        modeJob?.cancel()
        modeJob = null
        eventJob?.cancel()
        eventJob = null
        indicatorJob?.cancel()
        indicatorJob = null
        interruptJob?.cancel()
        interruptJob = null
        actionJob?.cancel()
        actionJob = null
        detachFromMountedPage()
        pageVoice?.stop()
        scope = null
        interruptingPage = null
        popupManager.clear()
        stack.clear()
        grant = ScreenGrant.NONE
        projectedOnAssumedGrant = false
        projectionMirror.clear()
    }

    /**
     * Mounts, redraws or withdraws the page claiming the screen.
     *
     * The claim is honoured whatever the mode: `projectActivePage` is what declines to write while
     * the tachometer owns the screen, so the page is already mounted when the cluster hands it over.
     */
    private suspend fun onInterruptChanged(page: PageProvider?) {
        when {
            page == null -> withdrawInterrupt()

            page === interruptingPage -> {
                Log.d("Interrupting page %s redrawn", page.name)
                projectActivePage()
            }

            else -> {
                Log.i("Page %s is claiming the screen", page.name)
                // A claim replaces the one before it; it does not pile on top of it.
                // `PriorityInterruptSource` never emits a `null` between two claimants — a message
                // holding the screen and then a call arriving is one emission after another — so
                // without this the first page stayed underneath the second, and every subpage it
                // had pushed with it. `Play` on the prompt page puts the reading on this very
                // stack, which is what makes those fossils reachable: one BACK off the call landed
                // on a message belonging to a claim that was already over.
                stack.clearInterrupt()
                interruptingPage = page
                stack.interrupt(page)
                projectActivePage()
            }
        }
    }

    private suspend fun withdrawInterrupt() {
        val withdrawn = interruptingPage ?: return
        Log.i("Page %s no longer claims the screen", withdrawn.name)
        interruptingPage = null
        stack.clearInterrupt()

        // A claim raised before the cluster ever handed the screen over leaves nothing on the
        // default stack, and withdrawing it would strand the cluster on the frame of a page that is
        // gone. The root goes up instead, for the same reason `pop` never empties the default stack.
        if (!stack.hasRoot && grant != ScreenGrant.NONE) {
            stack.installRoot(rootPageFactory())
        }
        projectActivePage()
    }

    /**
     * Lets go of the page the redraw, the voice, the cues and the work were following.
     *
     * All four jobs and [renderedPage] move together on purpose. Cancelling the jobs while still
     * remembering the page as attached would leave it **mute for good**: the next
     * `projectActivePage` would find `page === renderedPage`, launch nothing, and the one key of a
     * page that exists to speak would do nothing for the rest of the session.
     */
    private fun detachFromMountedPage() {
        redrawJob?.cancel()
        redrawJob = null
        speechJob?.cancel()
        speechJob = null
        cueJob?.cancel()
        cueJob = null
        workJob?.cancel()
        workJob = null
        renderedPage = null
    }

    /** Re-projects the active page without touching the stack. */
    suspend fun invalidate() = projectActivePage()

    private suspend fun onGrantChanged(granted: ScreenGrant) {
        grant = granted
        if (granted == ScreenGrant.NONE) {
            Log.i("Cluster returned to HU_MODE: suspending frame emission")
            // The stack is not cleared, so coming back re-projects the same page and the mirror
            // recovers it intact. What does not survive is the voice: a warning on the dashboard
            // takes the screen back mid-sentence, and a page the rider can
            // no longer see must not keep talking to him.
            detachFromMountedPage()
            projectionMirror.publishClusterOwnsScreen()
            return
        }

        // Not `stack.isEmpty`, which counts the interrupt stack too. A call already ringing when
        // the cluster hands the screen over claims that stack from HU_MODE, and reading it as
        // "there is already a page" left the default stack with no root at all: the first MENU
        // press then reset the interruption away, found nothing underneath and froze the cluster on
        // the frame it happened to be showing.
        if (granted == ScreenGrant.ASSUMED) {
            Log.w("Projecting without a 0x09: the cluster has not confirmed the handover")
            projectedOnAssumedGrant = true
        } else if (projectedOnAssumedGrant) {
            // Same coroutine as the write below, so the wipe cannot land after it.
            Log.i("Handover confirmed after projecting without it: writing the current page again")
            projectedOnAssumedGrant = false
            screenProjector.invalidateShownBase()
        }

        if (!stack.hasRoot) {
            Log.i("Entering SAB_MODE: mounting the root page")
            stack.installRoot(rootPageFactory())
        }
        projectActivePage()
    }

    private suspend fun onButtonPressed(functionId: com.opensab.sab.protocol.assignment.FunctionId) {
        if (grant == ScreenGrant.NONE) {
            return
        }
        if (popupManager.onInput(functionId)) {
            return
        }

        val page = stack.peek() ?: return
        apply(page.onInput(functionId))
    }

    /**
     * The one place a [NavigationAction] moves the stack, whether it came from a press or from the
     * work of the mounted page.
     */
    private suspend fun apply(action: NavigationAction) {
        when (action) {
            is NavigationAction.Next -> {
                stack.push(action.page)
                cuePlayer.play(Cue.ENTER)
                projectActivePage()
            }

            NavigationAction.Pop -> {
                if (stack.pop() != null) {
                    // Inside the `if` and not before it: a `Pop` on the root moves nothing, and a
                    // LEAVE there would announce a departure that did not happen.
                    cuePlayer.play(Cue.LEAVE)
                    projectActivePage()
                }
            }

            is NavigationAction.Replace -> {
                stack.replace(action.page)
                projectActivePage()
            }

            is NavigationAction.Interrupt -> {
                stack.interrupt(action.page)
                projectActivePage()
            }

            NavigationAction.Reset -> {
                stack.resetToRoot()
                projectActivePage()
            }

            NavigationAction.Invalidate -> projectActivePage()

            NavigationAction.None -> Unit
        }
    }

    private suspend fun projectActivePage() {
        val projectingOn = grant
        if (projectingOn == ScreenGrant.NONE) {
            return
        }
        val page = stack.peek() ?: return

        val s = scope
        if (s != null && page !== renderedPage) {
            redrawJob?.cancel()
            renderedPage = page
            redrawJob = s.launch {
                page.invalidations.collect { invalidate() }
            }

            // The voice lives and dies with the mount, next to the redraw. `collectLatest` and not
            // `collect`: a second script is a repeat, so it cancels the one still being said
            // instead of queueing behind it.
            speechJob?.cancel()
            speechJob = if (page is SpeakingPage && pageVoice != null) {
                s.launch { page.speech.collectLatest { pageVoice.say(it) } }
            } else {
                null
            }

            // The cues, next to the voice and with the same three deaths. Two differences, both
            // deliberate:
            //  - `collect` and not `collectLatest`: two cues are two events and both have to sound.
            //    Today `play` does not suspend so the two would behave alike, which is exactly why
            //    the honest one is used.
            //  - UNDISPATCHED: the flow drops what nobody is collecting, and the first cue of a
            //    mount — the LISTEN the dialler emits from `work()` — arrives microseconds later.
            //    This registers the subscription before this method returns, so `workJob` cannot
            //    outrun it.
            cueJob?.cancel()
            cueJob = if (page is CuingPage) {
                s.launch(start = CoroutineStart.UNDISPATCHED) {
                    page.cues.collect { cuePlayer.play(it) }
                }
            } else {
                null
            }

            // And the work, on the same mount and with the same three deaths. What it asks for
            // leaves by the channel: applying it here would cancel this very job.
            workJob?.cancel()
            workJob = if (page is WorkingPage) {
                s.launch { pageActions.send(page.work()) }
            } else {
                null
            }
        }

        val frame = page.render(requestIdGenerator.nextId())
        Log.i("Emitting projection frame for page %s (requestId=%d)", page.name, frame.requestId)

        // Mirror first, verdict after. That order is what makes a rejection leave the mirror
        // painted and marked stale, which is the truth: the cluster still shows the previous page.
        projectionMirror.publishPage(page.mirror(), stack.snapshot().map { it.name })
        val result = screenProjector.updateBaseScreen(frame)
            .onFailure { Log.w(it, "Could not project page %s", page.name) }
        projectionMirror.publishDelivery(mirrorDeliveryOf(result, handoverConfirmed = projectingOn == ScreenGrant.CONFIRMED))
    }
}
