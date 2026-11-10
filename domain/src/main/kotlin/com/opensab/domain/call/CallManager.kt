package com.opensab.domain.call

import com.opensab.domain.audio.AudioFocus
import com.opensab.domain.audio.AudioFocusKind
import com.opensab.domain.audio.AudioFocusLease
import com.opensab.domain.audio.NoAudioFocus
import com.opensab.logging.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Publishes the live call state and holds the call's audio focus, and that is all it owns: the
 * PHONE bit of the status bar is lit on the cluster side from [activeCall] (`CallIndicator`).
 */
interface CallManager {

    /** Watched by `CallInterruptSource` to decide when the call page claims the screen. */
    val activeCall: StateFlow<CallInfo?>

    /**
     * It does not listen to the handlebar and does not post a modal. An incoming call reaches the
     * rider as a page on the interrupt stack, and **that page owns the press** — `SELECT` answers
     * and `BACK` rejects through `CallPageProvider.onInput`, like every other page in the engine.
     * A collector here would be a second owner of the same press, which is what made the cluster
     * answer a call the mirror had labelled "dismiss".
     */
    fun start(scope: CoroutineScope)

    fun stop()
}

/**
 * Single owner of the live call — its status, its identity, its timestamp and its audio focus. The
 * platform edge translates and posts; it never decides and never keeps state.
 *
 * It answers to two types on purpose. [CallManager] is what the engine watches and [TelecomAdapter]
 * is what the call page acts through, and they are the same object because the commands that came
 * with the notification live next to the state they act on: "which call is live" and "how to act on
 * it" cannot drift apart if nobody can hold one without the other.
 *
 * @param notifications one instance per graph, built by `NotificationModule`.
 * @param nowMillis injected so the suite can move it and watch the timestamp not move.
 */
class DefaultCallManager(
    private val notifications: CallNotificationBus,
    private val audioFocus: AudioFocus = NoAudioFocus,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : CallManager, TelecomAdapter {

    /**
     * Identity is the notification key, which the platform keeps stable across RINGING and ACTIVE.
     * A repost under the same key is a `copy(...)` of the very same [CallInfo] and never a new one,
     * so `CallInterruptSource` re-emits one page instance for the whole call and the engine tells
     * pages apart by reference. The timestamp is stamped once, when the key first appears, so an
     * identical repost produces an **equal** value and this flow stays quiet.
     */
    private val _activeCall = MutableStateFlow<CallInfo?>(null)
    override val activeCall: StateFlow<CallInfo?> = _activeCall.asStateFlow()
    override val currentCall: StateFlow<CallInfo?> get() = activeCall

    private var currentKey: String? = null
    private var commands: CallCommands? = null

    private var busJob: Job? = null
    private var focusJob: Job? = null

    /**
     * The audio focus of the live call, held for the span of the call and not of the page that
     * shows it: the rider dismisses the page while the call goes on, and the call ends while he is
     * looking at something else. That span is this class's — it is the span of [activeCall], the
     * same one the cluster lights the PHONE bit for — so the focus lives next to the call instead of
     * acquiring a second owner.
     */
    private var focusLease: AudioFocusLease? = null

    /** Whether a call is live as far as the focus is concerned; a denied request still counts. */
    private var focusAsked = false

    override fun start(scope: CoroutineScope) {
        stop()
        Log.i("Starting CallManager service")

        busJob = scope.launch {
            notifications.events.collect { onEvent(it) }
        }

        // The music pauses for as long as a call is live. The cluster lights PHONE from this very
        // flow (`CallIndicator`), so the focus and the bit answer the same question and cannot
        // drift apart. `TRANSIENT` and not `MAY_DUCK`: a conversation the rider has to hold is not
        // something to talk over.
        focusJob = scope.launch {
            _activeCall.collect { call ->
                setCallAudioFocus(call != null)
            }
        }
    }

    override fun stop() {
        busJob?.cancel()
        busJob = null
        focusJob?.cancel()
        focusJob = null
        forget()
        // The collector that owned the focus is gone, so the last release has to be explicit, and
        // without a coroutine: a manager that has stopped can never be asked to give the music back
        // later.
        setCallAudioFocus(false)
    }

    /** Idempotent on both edges: thirteen reposts of one call take the focus once. */
    private fun setCallAudioFocus(callIsLive: Boolean) {
        if (callIsLive == focusAsked) return
        focusAsked = callIsLive
        if (callIsLive) {
            focusLease = audioFocus.request(AudioFocusKind.TRANSIENT)
        } else {
            focusLease?.abandon()
            focusLease = null
        }
    }

    override fun acceptCall() {
        Log.i("Answering the live call")
        commands?.answer()
    }

    override fun rejectCall() {
        Log.i("Rejecting the live call")
        commands?.reject()
    }

    override fun endCall() {
        Log.i("Hanging up the live call")
        commands?.disconnect()
    }

    private fun onEvent(event: CallNotificationEvent) = when (event) {
        is CallNotificationEvent.Posted -> onPosted(event)
        is CallNotificationEvent.Removed -> onRemoved(event.key)
        CallNotificationEvent.ListenerDisconnected -> {
            Log.w("Notification listener disconnected, dropping the live call")
            forget()
        }
    }

    private fun onPosted(event: CallNotificationEvent.Posted) {
        val parsed = CallNotificationParser.parse(event.notification) ?: return
        val key = event.notification.key

        // The commands are refreshed on every repost even when the call did not move: `CallStyle`
        // swaps answer/decline for hangUp the moment the call is answered, so the newest
        // notification is the only one holding intents that still work.
        commands = event.commands

        val call = if (key == currentKey) {
            // Same call. The timestamp means "when this call showed up", so it survives.
            _activeCall.value?.copy(
                callerName = parsed.callerName,
                callerNumber = parsed.callerNumber,
                status = parsed.status
            ) ?: parsed.copy(timestamp = nowMillis())
        } else {
            Log.d("Call from %s (%s)", parsed.callerName, parsed.status)
            currentKey = key
            parsed.copy(timestamp = nowMillis())
        }

        _activeCall.value = call
    }

    private fun onRemoved(key: String) {
        if (key != currentKey) return
        Log.d("Call %s is gone", key)
        forget()
    }

    private fun forget() {
        currentKey = null
        commands = null
        _activeCall.value = null
    }
}
