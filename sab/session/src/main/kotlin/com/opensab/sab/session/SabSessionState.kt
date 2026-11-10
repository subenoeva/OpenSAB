package com.opensab.sab.session

import com.opensab.sab.session.crypto.SecuritySession
import com.opensab.sab.session.vehicle.KnownVehicle

sealed interface SabSessionState {

    data object Disconnected : SabSessionState

    data class Connecting(val step: ConnectionStep) : SabSessionState

    data class Connected(
        val vehicle: KnownVehicle,
        val session: SecuritySession
    ) : SabSessionState

    data class Error(val cause: Throwable) : SabSessionState
}

/**
 * The stages [SabSessionState.Connecting] walks through, named rather than worded.
 *
 * The service layer has no Context and cannot know the rider's language, so it reports which stage
 * is running and the UI resolves the name against `strings.xml`.
 */
enum class ConnectionStep {
    NEGOTIATING_MTU,
    ESTABLISHING_SECURITY,
    READING_VEHICLE_IDENTITY,
    ARMING_CHANNELS,
    RUNNING_ASSIGNMENT,
    STARTING_ADAPTERS,

    /** Between a failed attempt and the next one, while the motorcycle is still linked. */
    WAITING_TO_RETRY
}
