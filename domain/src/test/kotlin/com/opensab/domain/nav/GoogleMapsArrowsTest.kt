package com.opensab.domain.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleMapsArrowsTest {

    /** The maneuver drawings in the Maps 26.38 APK (`aapt2 dump resources`, 2026-09-26). */
    private val drawablesInMaps2638 = setOf(
        "arrive_left", "ic_arrive_left", "ic_arrive_right", "maneuver_depart", "maneuver_destination",
        "maneuver_destination_left", "maneuver_destination_right", "maneuver_destination_straight",
        "maneuver_fork_left", "maneuver_fork_right", "maneuver_keep_left", "maneuver_keep_right",
        "maneuver_merge", "maneuver_merge_left", "maneuver_merge_right", "maneuver_name_change",
        "maneuver_off_ramp_keep_left", "maneuver_off_ramp_keep_right", "maneuver_off_ramp_normal_left",
        "maneuver_off_ramp_normal_right", "maneuver_off_ramp_sharp_left", "maneuver_off_ramp_sharp_right",
        "maneuver_off_ramp_slight_left", "maneuver_off_ramp_slight_right", "maneuver_off_ramp_u_turn_left",
        "maneuver_off_ramp_u_turn_right", "maneuver_on_ramp_keep_left", "maneuver_on_ramp_keep_right",
        "maneuver_on_ramp_normal_left", "maneuver_on_ramp_normal_right", "maneuver_on_ramp_sharp_left",
        "maneuver_on_ramp_sharp_right", "maneuver_on_ramp_slight_left", "maneuver_on_ramp_slight_right",
        "maneuver_on_ramp_u_turn_left", "maneuver_on_ramp_u_turn_right",
        "maneuver_roundabout_enter_and_exit_ccw", "maneuver_roundabout_enter_and_exit_ccw_normal_left",
        "maneuver_roundabout_enter_and_exit_ccw_normal_right", "maneuver_roundabout_enter_and_exit_ccw_sharp_left",
        "maneuver_roundabout_enter_and_exit_ccw_sharp_right", "maneuver_roundabout_enter_and_exit_ccw_slight_left",
        "maneuver_roundabout_enter_and_exit_ccw_slight_right", "maneuver_roundabout_enter_and_exit_ccw_straight",
        "maneuver_roundabout_enter_and_exit_ccw_u_turn", "maneuver_roundabout_enter_and_exit_cw",
        "maneuver_roundabout_enter_and_exit_cw_normal_left", "maneuver_roundabout_enter_and_exit_cw_normal_right",
        "maneuver_roundabout_enter_and_exit_cw_sharp_left", "maneuver_roundabout_enter_and_exit_cw_sharp_right",
        "maneuver_roundabout_enter_and_exit_cw_slight_left", "maneuver_roundabout_enter_and_exit_cw_slight_right",
        "maneuver_roundabout_enter_and_exit_cw_straight", "maneuver_roundabout_enter_and_exit_cw_u_turn",
        "maneuver_roundabout_enter_ccw", "maneuver_roundabout_enter_cw", "maneuver_roundabout_exit_ccw",
        "maneuver_roundabout_exit_cw", "maneuver_straight", "maneuver_turn_normal_left",
        "maneuver_turn_normal_right", "maneuver_turn_sharp_left", "maneuver_turn_sharp_right",
        "maneuver_turn_slight_left", "maneuver_turn_slight_right", "maneuver_u_turn_left", "maneuver_u_turn_right"
    )

    @Test
    fun everyManeuverDrawingInMapsHasAType() {
        assertEquals(drawablesInMaps2638, GoogleMapsArrows.byDrawable.keys)
    }

    /** ccw is driving on the right (Spain): the figure without `_CW`, `DA_TURN_ROUNDABOUT_n`. */
    @Test
    fun counterclockwiseRoundaboutsAreTheExitAngle() {
        val prefix = "maneuver_roundabout_enter_and_exit_ccw_"
        assertEquals(ManeuverType.ROUNDABOUT_SHARP_RIGHT, GoogleMapsArrows.byDrawable[prefix + "sharp_right"])
        assertEquals(ManeuverType.ROUNDABOUT_RIGHT, GoogleMapsArrows.byDrawable[prefix + "normal_right"])
        assertEquals(ManeuverType.ROUNDABOUT_STRAIGHT, GoogleMapsArrows.byDrawable[prefix + "straight"])
        assertEquals(ManeuverType.ROUNDABOUT_LEFT, GoogleMapsArrows.byDrawable[prefix + "normal_left"])
        assertEquals(ManeuverType.ROUNDABOUT_UTURN, GoogleMapsArrows.byDrawable[prefix + "u_turn"])
        assertEquals(ManeuverType.ROUNDABOUT, GoogleMapsArrows.byDrawable["maneuver_roundabout_enter_ccw"])
        assertEquals(ManeuverType.ROUNDABOUT_EXIT, GoogleMapsArrows.byDrawable["maneuver_roundabout_exit_ccw"])
    }

    @Test
    fun clockwiseRoundaboutsAreTheMirroredFigure() {
        assertEquals(
            ManeuverType.ROUNDABOUT_UTURN_CW,
            GoogleMapsArrows.byDrawable["maneuver_roundabout_enter_and_exit_cw_u_turn"]
        )
        assertEquals(ManeuverType.ROUNDABOUT_CW, GoogleMapsArrows.byDrawable["maneuver_roundabout_enter_and_exit_cw"])
    }

    /** Measured: ramps draw the same arrow as the plain turn, except the normal off-ramp. */
    @Test
    fun rampsForksAndArrivalsFollowTheirDrawing() {
        val arrows = GoogleMapsArrows.byDrawable
        assertEquals(ManeuverType.TURN_LEFT, arrows["maneuver_on_ramp_normal_left"])
        assertEquals(ManeuverType.RAMP_RIGHT, arrows["maneuver_off_ramp_normal_right"])
        assertEquals(ManeuverType.TURN_SHARP_RIGHT, arrows["maneuver_off_ramp_sharp_right"])
        assertEquals(ManeuverType.FORK_LEFT, arrows["maneuver_keep_left"])
        assertEquals(ManeuverType.FORK_RIGHT, arrows["maneuver_off_ramp_keep_right"])
        assertEquals(ManeuverType.UTURN, arrows["maneuver_u_turn_left"])
        assertEquals(ManeuverType.UTURN_RIGHT, arrows["maneuver_on_ramp_u_turn_right"])
        assertEquals(ManeuverType.STRAIGHT, arrows["maneuver_name_change"])
        assertEquals(ManeuverType.DEPART, arrows["maneuver_depart"])
        assertEquals(ManeuverType.MERGE, arrows["maneuver_merge_left"])
        assertEquals(ManeuverType.DESTINATION_LEFT, arrows["arrive_left"])
        assertEquals(ManeuverType.DESTINATION, arrows["maneuver_destination_straight"])
    }
}
