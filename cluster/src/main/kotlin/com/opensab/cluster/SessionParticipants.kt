package com.opensab.cluster

import com.opensab.cluster.indicators.IndicatorTranslator
import com.opensab.domain.music.MusicManager
import com.opensab.logging.Log
import com.opensab.sab.session.SabSession
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.SessionParticipant
import com.opensab.sab.session.coroutines.runCatchingCancellable
import com.opensab.sab.session.input.InputDispatcher
import com.opensab.sab.session.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The projection engine, the one participant whose start needs more than the session's scope. */
class EngineParticipant(
    private val engine: BaseStateManager,
    private val input: InputDispatcher
) : SessionParticipant {
    override val name: String = "engine"

    override fun onSessionStarted(session: SabSession) {
        engine.start(input, session.scope)
    }

    override fun onSessionClosing(session: SabSession) {
        engine.stop()
    }
}

/** A phone-side source whose whole lifecycle is `start(scope)` / `stop()`. */
class StartStopParticipant(
    override val name: String,
    private val start: (CoroutineScope) -> Unit,
    private val stop: () -> Unit
) : SessionParticipant {
    override fun onSessionStarted(session: SabSession) {
        start(session.scope)
    }

    override fun onSessionClosing(session: SabSession) {
        stop()
    }
}

/**
 * The status bar, lit on the cluster side from what the sources expose. Started after the sources,
 * so each translator reads a state its source has already settled — the live calls the call bus
 * hands a late collector included. No close action: the collectors die with [SabSession.scope]
 * and `ActiveIndicatorsPublisher.forget()` leaves the mask at zero for the next session.
 */
class IndicatorsParticipant(
    private val translators: List<IndicatorTranslator>
) : SessionParticipant {
    override val name: String = "indicators"

    override fun onSessionStarted(session: SabSession) {
        translators.forEach { it.start(session.scope) }
    }

    override fun onSessionClosing(session: SabSession) = Unit
}

/**
 * Saves the motorcycle a session just paired with, so it is offered on the next launch. Only a
 * session the scanner opened has a vehicle worth
 * saving — the receiver only ever reconnects one already known — so this is the one participant
 * that reads [SabSession.origin] before doing anything.
 *
 * Runs on [SabSession.scope] rather than blocking [onSessionStarted]: the write can outlive the
 * screen that triggered it exactly the way the rest of the session does.
 */
class KnownVehicleParticipant(
    private val vehicleRepository: VehicleRepository
) : SessionParticipant {
    override val name: String = "known-vehicle"

    override fun onSessionStarted(session: SabSession) {
        if (session.origin != SessionOrigin.SCANNER) return
        val vehicle = session.connected?.vehicle ?: return
        session.scope.launch {
            runCatchingCancellable { vehicleRepository.saveVehicle(vehicle) }
                .onFailure { Log.e(it, "Could not persist the paired vehicle ${vehicle.macAddress}") }
        }
    }

    override fun onSessionClosing(session: SabSession) = Unit
}

/**
 * "Auto-Resume Playback". Started right after the music participant, so the adapter is attached when
 * `play()` arrives. `play()` goes through the same funnel as a handlebar Play: while the headless
 * engine is connecting or buffering a second one is ignored, so opening the music page during this
 * cold start waits for it instead of starting another.
 *
 * Owner of the launched coroutine: the session. It runs on [SabSession.scope] and dies with it, so a
 * session that closes first takes the pending play with it. `status` is read after the suspending
 * settings and favourite reads, so it is the freshest; a stray `play()` on an already PLAYING
 * player is harmless.
 */
class AutoResumeMusicParticipant(
    private val musicManager: MusicManager,
    private val enabled: suspend () -> Boolean
) : SessionParticipant {
    override val name: String = "auto-resume"

    override fun onSessionStarted(session: SabSession) {
        session.scope.launch {
            if (enabled()) {
                Log.i("Auto-resuming the favourite player on connect")
                musicManager.play()
            }
        }
    }

    override fun onSessionClosing(session: SabSession) = Unit
}
