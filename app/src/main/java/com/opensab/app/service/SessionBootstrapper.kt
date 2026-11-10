package com.opensab.app.service

import com.opensab.logging.Log
import com.opensab.sab.session.SessionOrigin
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * How the ACL receiver opens a session so that its foreground service gets the microphone.
 *
 * Android grants `microphone` only to a service started while the app is visible, and the ACL
 * broadcast is not visible: started from the receiver, dictation records zeros. So the receiver
 * launches the opaque `SessionBootstrapActivity` and the START leaves from
 * there, once it is resumed ([startClaimed]).
 *
 * The receiver never loses the session over it. Without the overlay permission that exempts the
 * launch, or if [launchActivity] reports a refusal, the START goes out at once the old way; and since Android
 * may also block the launch in silence, a START the activity has not claimed within [claimTimeout]
 * goes out the old way too. Either way the session opens without a microphone, which
 * [ForegroundPromoter] recovers the next time the app is visible.
 *
 * **Owner of the pending START and of its fallback timer**: this class alone. The lock makes the
 * claim and the timeout exclusive, so a session is started once whichever side comes first.
 */
class SessionBootstrapper(
    private val launcher: SessionLauncher,
    private val overlayGranted: () -> Boolean,
    private val launchActivity: () -> Boolean,
    private val scope: CoroutineScope,
    private val claimTimeout: Duration
) {

    private class Pending(val macAddress: String, val claim: CompletableJob)

    private val lock = Any()
    private var pending: Pending? = null
    private var fallback: Job? = null

    /**
     * Completes once this claim is over: the START has left, from the activity or from the fallback,
     * or a [disconnect] or a later [connect] dropped it. Waiting on it never cancels the fallback.
     */
    fun connect(macAddress: String): Job {
        if (!overlayGranted()) {
            Log.i("No overlay permission, starting the session from the receiver without a microphone")
            launcher.start(macAddress, SessionOrigin.RECEIVER)
            return Job().apply { complete() }
        }
        val claim = Job()
        synchronized(lock) {
            takePending()?.claim?.complete()
            pending = Pending(macAddress, claim)
            fallback = scope.launch {
                delay(claimTimeout)
                takePending()?.let {
                    Log.w("Bootstrap activity did not arrive within %s, starting the session from the receiver", claimTimeout)
                    start(it)
                }
            }
        }
        if (!launchActivity()) {
            takePending()?.let {
                Log.w("Bootstrap activity could not be launched, starting the session from the receiver")
                start(it)
            }
        }
        return claim
    }

    fun disconnect() {
        takePending()?.claim?.complete()
        launcher.stop(SessionOrigin.RECEIVER)
    }

    /** Called by the resumed activity. False if the START already left, or was cancelled. */
    fun startClaimed(): Boolean {
        val claimed = takePending() ?: return false
        Log.i("Bootstrap activity visible, starting the session with the app on top")
        start(claimed)
        return true
    }

    private fun start(claimed: Pending) {
        try {
            launcher.start(claimed.macAddress, SessionOrigin.RECEIVER)
        } finally {
            claimed.claim.complete()
        }
    }

    private fun takePending(): Pending? = synchronized(lock) {
        val taken = pending
        pending = null
        fallback?.cancel()
        fallback = null
        taken
    }
}
