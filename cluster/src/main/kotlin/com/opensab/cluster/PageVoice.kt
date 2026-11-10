package com.opensab.cluster

import com.opensab.domain.audio.AudioFocus
import com.opensab.domain.audio.AudioFocusKind
import com.opensab.domain.audio.AudioFocusLease
import com.opensab.domain.audio.NoAudioFocus
import com.opensab.domain.settings.VoiceVerbosity
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechSynthesizer
import com.opensab.domain.speech.SpeechTextResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Says a script out loud, one phrase after another, and nothing else.
 *
 * **Single owner of the voice while a page is mounted.** [BaseStateManager] holds the only job that
 * calls this; it cancels that job when the mounted page changes and calls [stop] on its own `stop()`.
 * Nobody else may call [SpeechSynthesizer.speak] while a page is up, or one speaker would have two
 * owners.
 *
 * **Single owner of the ducking, too.** One lease covers the whole script, not one per phrase, and
 * it outlives the script by [releaseDebounce]: the carousel says one phrase per move, and asking
 * for the focus and giving it back on each would make the music pump under a fast scroll. A script
 * that starts inside the window keeps the lease it finds.
 *
 * With [inCall] true nothing is said and the lease goes back at once: over SCO the helmet
 * microphone would carry the narration into the conversation. A ringing call does not count.
 *
 * Nothing here catches anything. A cancelled utterance is the rider pressing BACK, and the
 * `CancellationException` has to reach the job that launched it.
 *
 * @param sessionScope where the deferred release runs, owned by the graph. It must outlive any one
 *   script, because the release is scheduled from the `finally` of a script being cancelled.
 */
class PageVoice(
    private val resolver: SpeechTextResolver,
    private val synthesizer: SpeechSynthesizer,
    private val audioFocus: AudioFocus = NoAudioFocus,
    private val verbosity: () -> VoiceVerbosity = { VoiceVerbosity.STANDARD },
    private val inCall: () -> Boolean = { false },
    private val releaseDebounce: Duration = RELEASE_DEBOUNCE,
    private val sessionScope: CoroutineScope
) {
    /** Guards the three fields below; nothing reads or writes them outside it. */
    private val leaseMutex = Mutex()
    private var activeLease: AudioFocusLease? = null
    private var releaseJob: Job? = null

    /**
     * Set by [stop] so a script cut by that same `stop()` does not schedule a release after the
     * lease is gone. Cleared by the next [say]: this instance outlives the session (it is a
     * singleton of the graph), so the next session has to be able to speak again.
     */
    private var isStopped = false

    /** Suspends until the whole script has been said, or until the caller is cancelled. */
    suspend fun say(script: List<SpeechRequest>) {
        if (script.isEmpty()) return
        val currentVerbosity = verbosity()
        if (currentVerbosity == VoiceVerbosity.NONE) return

        if (inCall()) {
            withContext(NonCancellable) { leaseMutex.withLock { releaseNow() } }
            return
        }

        leaseMutex.withLock {
            isStopped = false
            releaseJob?.cancel()
            releaseJob = null
            if (activeLease == null) {
                activeLease = audioFocus.request(AudioFocusKind.TRANSIENT_MAY_DUCK)
            }
        }

        try {
            for (request in script) {
                if (inCall()) break
                val text = resolver.resolve(request, currentVerbosity)
                if (text.isNotBlank()) {
                    synthesizer.speak(text, request.locale)
                }
            }
        } finally {
            // Runs on cancellation too, which is the common path: every move of the cursor cancels
            // the script before. `NonCancellable` so the release is still scheduled while this
            // coroutine is being torn down; the `CancellationException` leaves untouched.
            withContext(NonCancellable) {
                leaseMutex.withLock {
                    if (!isStopped) scheduleRelease()
                }
            }
        }
    }

    /**
     * Gives the lease back now and cancels any pending release. Called by the engine's `stop()`,
     * which is not suspending: undispatched, so an uncontended lock releases before this returns.
     */
    fun stop() {
        sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                leaseMutex.withLock {
                    isStopped = true
                    releaseNow()
                }
            }
        }
    }

    /** Caller holds [leaseMutex]. */
    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = sessionScope.launch {
            delay(releaseDebounce)
            leaseMutex.withLock {
                if (isActive && !isStopped) {
                    activeLease?.abandon()
                    activeLease = null
                }
            }
        }
    }

    /** Caller holds [leaseMutex]. */
    private fun releaseNow() {
        releaseJob?.cancel()
        releaseJob = null
        activeLease?.abandon()
        activeLease = null
    }

    private companion object {
        val RELEASE_DEBOUNCE: Duration = 500.milliseconds
    }
}
