package com.opensab.cluster

import com.opensab.domain.audio.Cue
import com.opensab.domain.audio.CuePlayer
import com.opensab.domain.audio.HeadsetPresence
import com.opensab.logging.Log
import com.opensab.sab.session.SabSession
import com.opensab.sab.session.SessionAnnouncer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The link cue is a pair: LINK_DOWN sounds only for a session whose LINK_UP was announced.
 *
 * LINK_UP waits for two things at once. For the rider's headset (`CUE-UNHEARD`): the intercom often
 * joins A2DP after the session is up, and a cue on the phone's speaker is not heard in the helmet;
 * past [HEADSET_WAIT] it sounds wherever the phone routes it. And for [settle]: a link the BTU drops
 * within seconds of making it is not worth telling the rider about, in either direction. Both waits
 * run on `session.scope`, so a session that closes first takes its LINK_UP with it, and its
 * LINK_DOWN too.
 *
 * **Owner of [announcedSessionId]**: this class. Written from the announcing coroutine on
 * `session.scope` and cleared by [announceClosing], which the session's owner calls before it closes
 * that scope.
 */
class CueSessionAnnouncer(
    private val cuePlayer: CuePlayer,
    private val headset: HeadsetPresence,
    private val settle: Duration
) : SessionAnnouncer {

    private val announcedSessionId = AtomicLong(NO_SESSION)

    override fun announceOpened(session: SabSession) {
        session.scope.launch {
            val settled = launch { delay(settle) }
            val waiting = TimeSource.Monotonic.markNow()
            val arrived = withTimeoutOrNull(HEADSET_WAIT) { headset.connected().first { it } }
            if (arrived == null) {
                Log.i("No headset %d ms after the link came up: LINK_UP takes the phone's route", HEADSET_WAIT.inWholeMilliseconds)
            } else {
                Log.i("Headset output available %d ms after the link came up", waiting.elapsedNow().inWholeMilliseconds)
            }
            settled.join()
            announcedSessionId.set(session.id)
            cuePlayer.play(Cue.LINK_UP)
        }
    }

    override fun announceClosing(session: SabSession) {
        if (session.connected == null) return
        if (announcedSessionId.compareAndSet(session.id, NO_SESSION)) {
            cuePlayer.play(Cue.LINK_DOWN)
        } else {
            Log.i("Link cue %s skipped: %s", Cue.LINK_DOWN, "session #${session.id} closed before its LINK_UP")
        }
    }

    companion object {
        /**
         * 1.5x the only tight bound in the captures of 2026-09-24 and 2026-09-26: in s2 the headset was
         * up at most 6.6 s after LINK_UP. The "Headset output available" line measures the real arrival.
         */
        val HEADSET_WAIT = 10.seconds

        /**
         * A debounce, not a verdict on the link. In the logs of 2026-10-01 to 2026-10-08 the three
         * sessions the BTU dropped right after connecting lived 2.3 s, 2.3 s and 6.1 s past
         * `connected successfully`; the only one with the headset already up was a 2.3 s one.
         */
        val LINK_SETTLE = 3.seconds

        private const val NO_SESSION = -1L
    }
}
