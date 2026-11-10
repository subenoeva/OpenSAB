package com.opensab.cluster.icons

import com.opensab.domain.nav.ManeuverType
import com.opensab.sab.protocol.projection.IconId

/**
 * The cluster, not the source, decides the [IconId]. `DA_TURN_ROUNDABOUT_n` is the exit angle on a
 * counterclockwise roundabout, and `_LEFT` its mirror, for driving on the left.
 */
fun ManeuverType.toProjectionIconId(): IconId = when (this) {
    ManeuverType.STRAIGHT, ManeuverType.UNKNOWN -> IconId.DA_TURN_STRAIGHT
    ManeuverType.DEPART -> IconId.DA_TURN_DEPART
    ManeuverType.TURN_SLIGHT_LEFT -> IconId.DA_TURN_SLIGHT_LEFT
    ManeuverType.TURN_LEFT -> IconId.DA_TURN_LEFT
    ManeuverType.TURN_SHARP_LEFT -> IconId.DA_TURN_SHARP_LEFT
    ManeuverType.TURN_SLIGHT_RIGHT -> IconId.DA_TURN_SLIGHT_RIGHT
    ManeuverType.TURN_RIGHT -> IconId.DA_TURN_RIGHT
    ManeuverType.TURN_SHARP_RIGHT -> IconId.DA_TURN_SHARP_RIGHT
    ManeuverType.UTURN -> IconId.DA_TURN_UTURN
    ManeuverType.UTURN_RIGHT -> IconId.DA_TURN_UTURN_LEFT
    ManeuverType.FORK_LEFT -> IconId.DA_TURN_FORK_LEFT
    ManeuverType.FORK_RIGHT -> IconId.DA_TURN_FORK_RIGHT
    ManeuverType.RAMP_LEFT -> IconId.DA_TURN_RAMP_LEFT
    ManeuverType.RAMP_RIGHT -> IconId.DA_TURN_RAMP_RIGHT
    ManeuverType.MERGE -> IconId.DA_TURN_GENERIC_MERGE
    ManeuverType.ROUNDABOUT -> IconId.DA_TURN_GENERIC_ROUNDABOUT
    ManeuverType.ROUNDABOUT_CW -> IconId.DA_TURN_GENERIC_ROUNDABOUT_LEFT
    ManeuverType.ROUNDABOUT_EXIT -> IconId.DA_TURN_ROUNDABOUT_EXIT
    ManeuverType.ROUNDABOUT_EXIT_CW -> IconId.DA_TURN_ROUNDABOUT_EXIT_LEFT
    ManeuverType.ROUNDABOUT_SHARP_RIGHT -> IconId.DA_TURN_ROUNDABOUT_1
    ManeuverType.ROUNDABOUT_RIGHT -> IconId.DA_TURN_ROUNDABOUT_2
    ManeuverType.ROUNDABOUT_SLIGHT_RIGHT -> IconId.DA_TURN_ROUNDABOUT_3
    ManeuverType.ROUNDABOUT_STRAIGHT -> IconId.DA_TURN_ROUNDABOUT_4
    ManeuverType.ROUNDABOUT_SLIGHT_LEFT -> IconId.DA_TURN_ROUNDABOUT_5
    ManeuverType.ROUNDABOUT_LEFT -> IconId.DA_TURN_ROUNDABOUT_6
    ManeuverType.ROUNDABOUT_SHARP_LEFT -> IconId.DA_TURN_ROUNDABOUT_7
    ManeuverType.ROUNDABOUT_UTURN -> IconId.DA_TURN_ROUNDABOUT_8
    ManeuverType.ROUNDABOUT_SHARP_RIGHT_CW -> IconId.DA_TURN_ROUNDABOUT_1_LEFT
    ManeuverType.ROUNDABOUT_RIGHT_CW -> IconId.DA_TURN_ROUNDABOUT_2_LEFT
    ManeuverType.ROUNDABOUT_SLIGHT_RIGHT_CW -> IconId.DA_TURN_ROUNDABOUT_3_LEFT
    ManeuverType.ROUNDABOUT_STRAIGHT_CW -> IconId.DA_TURN_ROUNDABOUT_4_LEFT
    ManeuverType.ROUNDABOUT_SLIGHT_LEFT_CW -> IconId.DA_TURN_ROUNDABOUT_5_LEFT
    ManeuverType.ROUNDABOUT_LEFT_CW -> IconId.DA_TURN_ROUNDABOUT_6_LEFT
    ManeuverType.ROUNDABOUT_SHARP_LEFT_CW -> IconId.DA_TURN_ROUNDABOUT_7_LEFT
    ManeuverType.ROUNDABOUT_UTURN_CW -> IconId.DA_TURN_ROUNDABOUT_8_LEFT
    ManeuverType.DESTINATION -> IconId.DA_TURN_ARRIVE
    ManeuverType.DESTINATION_LEFT -> IconId.DA_TURN_ARRIVE_LEFT
    ManeuverType.DESTINATION_RIGHT -> IconId.DA_TURN_ARRIVE_RIGHT
}
