package com.opensab.domain.nav

import com.opensab.domain.nav.ManeuverType.*

/**
 * Google Maps' maneuver drawings, by resource name, and what each one means. The keys
 * are what `MapsArrowCatalog` loads from the installed Maps; the values follow cluster turn figures.
 */
object GoogleMapsArrows {

    val byDrawable: Map<String, ManeuverType> = buildMap {
        fun sided(prefixes: List<String>, left: ManeuverType, right: ManeuverType) = prefixes.forEach {
            put("${it}_left", left)
            put("${it}_right", right)
        }

        put("maneuver_straight", STRAIGHT)
        put("maneuver_name_change", STRAIGHT)
        put("maneuver_depart", DEPART)
        sided(listOf("maneuver_turn_slight", "maneuver_on_ramp_slight", "maneuver_off_ramp_slight"), TURN_SLIGHT_LEFT, TURN_SLIGHT_RIGHT)
        sided(listOf("maneuver_turn_normal", "maneuver_on_ramp_normal"), TURN_LEFT, TURN_RIGHT)
        sided(listOf("maneuver_turn_sharp", "maneuver_on_ramp_sharp", "maneuver_off_ramp_sharp"), TURN_SHARP_LEFT, TURN_SHARP_RIGHT)
        sided(listOf("maneuver_u_turn", "maneuver_on_ramp_u_turn", "maneuver_off_ramp_u_turn"), UTURN, UTURN_RIGHT)
        sided(listOf("maneuver_fork", "maneuver_keep", "maneuver_on_ramp_keep", "maneuver_off_ramp_keep"), FORK_LEFT, FORK_RIGHT)
        sided(listOf("maneuver_off_ramp_normal"), RAMP_LEFT, RAMP_RIGHT)
        put("maneuver_merge", MERGE)
        sided(listOf("maneuver_merge"), MERGE, MERGE)

        put("maneuver_roundabout_enter_ccw", ROUNDABOUT)
        put("maneuver_roundabout_enter_and_exit_ccw", ROUNDABOUT)
        put("maneuver_roundabout_enter_cw", ROUNDABOUT_CW)
        put("maneuver_roundabout_enter_and_exit_cw", ROUNDABOUT_CW)
        put("maneuver_roundabout_exit_ccw", ROUNDABOUT_EXIT)
        put("maneuver_roundabout_exit_cw", ROUNDABOUT_EXIT_CW)
        listOf(
            "sharp_right" to (ROUNDABOUT_SHARP_RIGHT to ROUNDABOUT_SHARP_RIGHT_CW),
            "normal_right" to (ROUNDABOUT_RIGHT to ROUNDABOUT_RIGHT_CW),
            "slight_right" to (ROUNDABOUT_SLIGHT_RIGHT to ROUNDABOUT_SLIGHT_RIGHT_CW),
            "straight" to (ROUNDABOUT_STRAIGHT to ROUNDABOUT_STRAIGHT_CW),
            "slight_left" to (ROUNDABOUT_SLIGHT_LEFT to ROUNDABOUT_SLIGHT_LEFT_CW),
            "normal_left" to (ROUNDABOUT_LEFT to ROUNDABOUT_LEFT_CW),
            "sharp_left" to (ROUNDABOUT_SHARP_LEFT to ROUNDABOUT_SHARP_LEFT_CW),
            "u_turn" to (ROUNDABOUT_UTURN to ROUNDABOUT_UTURN_CW)
        ).forEach { (angle, types) ->
            put("maneuver_roundabout_enter_and_exit_ccw_$angle", types.first)
            put("maneuver_roundabout_enter_and_exit_cw_$angle", types.second)
        }

        put("maneuver_destination", DESTINATION)
        put("maneuver_destination_straight", DESTINATION)
        put("maneuver_destination_left", DESTINATION_LEFT)
        put("arrive_left", DESTINATION_LEFT)
        put("ic_arrive_left", DESTINATION_LEFT)
        put("maneuver_destination_right", DESTINATION_RIGHT)
        put("ic_arrive_right", DESTINATION_RIGHT)
    }
}
