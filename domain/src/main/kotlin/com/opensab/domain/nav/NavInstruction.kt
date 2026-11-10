package com.opensab.domain.nav

/**
 * What the cluster can draw for a maneuver, one value per `DA_TURN_*` figure. The
 * source knows no icon: the cluster maps each value to its icon in `icons/ManeuverIcons`.
 * `_CW` is a clockwise roundabout, the one driven on the left.
 */
enum class ManeuverType {
    STRAIGHT,
    DEPART,
    TURN_SLIGHT_LEFT,
    TURN_LEFT,
    TURN_SHARP_LEFT,
    TURN_SLIGHT_RIGHT,
    TURN_RIGHT,
    TURN_SHARP_RIGHT,
    UTURN,
    UTURN_RIGHT,
    FORK_LEFT,
    FORK_RIGHT,
    RAMP_LEFT,
    RAMP_RIGHT,
    MERGE,
    ROUNDABOUT,
    ROUNDABOUT_CW,
    ROUNDABOUT_EXIT,
    ROUNDABOUT_EXIT_CW,
    ROUNDABOUT_SHARP_RIGHT,
    ROUNDABOUT_RIGHT,
    ROUNDABOUT_SLIGHT_RIGHT,
    ROUNDABOUT_STRAIGHT,
    ROUNDABOUT_SLIGHT_LEFT,
    ROUNDABOUT_LEFT,
    ROUNDABOUT_SHARP_LEFT,
    ROUNDABOUT_UTURN,
    ROUNDABOUT_SHARP_RIGHT_CW,
    ROUNDABOUT_RIGHT_CW,
    ROUNDABOUT_SLIGHT_RIGHT_CW,
    ROUNDABOUT_STRAIGHT_CW,
    ROUNDABOUT_SLIGHT_LEFT_CW,
    ROUNDABOUT_LEFT_CW,
    ROUNDABOUT_SHARP_LEFT_CW,
    ROUNDABOUT_UTURN_CW,
    DESTINATION,
    DESTINATION_LEFT,
    DESTINATION_RIGHT,
    UNKNOWN
}

data class NavInstruction(
    val title: String,
    val streetName: String,
    val distanceFormatted: String,
    val maneuver: ManeuverType,
    val etaFormatted: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
