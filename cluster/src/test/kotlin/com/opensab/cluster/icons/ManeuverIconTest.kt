package com.opensab.cluster.icons

import com.opensab.domain.nav.ManeuverType
import com.opensab.sab.protocol.projection.IconId
import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverIconTest {

    /** Maneuver provider semantics: `_LEFT` is the mirrored figure. */
    @Test
    fun `every maneuver projects its expected cluster icon`() {
        val expected = mapOf(
            ManeuverType.STRAIGHT to IconId.DA_TURN_STRAIGHT,
            ManeuverType.DEPART to IconId.DA_TURN_DEPART,
            ManeuverType.TURN_SLIGHT_LEFT to IconId.DA_TURN_SLIGHT_LEFT,
            ManeuverType.TURN_LEFT to IconId.DA_TURN_LEFT,
            ManeuverType.TURN_SHARP_LEFT to IconId.DA_TURN_SHARP_LEFT,
            ManeuverType.TURN_SLIGHT_RIGHT to IconId.DA_TURN_SLIGHT_RIGHT,
            ManeuverType.TURN_RIGHT to IconId.DA_TURN_RIGHT,
            ManeuverType.TURN_SHARP_RIGHT to IconId.DA_TURN_SHARP_RIGHT,
            ManeuverType.UTURN to IconId.DA_TURN_UTURN,
            ManeuverType.UTURN_RIGHT to IconId.DA_TURN_UTURN_LEFT,
            ManeuverType.FORK_LEFT to IconId.DA_TURN_FORK_LEFT,
            ManeuverType.FORK_RIGHT to IconId.DA_TURN_FORK_RIGHT,
            ManeuverType.RAMP_LEFT to IconId.DA_TURN_RAMP_LEFT,
            ManeuverType.RAMP_RIGHT to IconId.DA_TURN_RAMP_RIGHT,
            ManeuverType.MERGE to IconId.DA_TURN_GENERIC_MERGE,
            ManeuverType.ROUNDABOUT to IconId.DA_TURN_GENERIC_ROUNDABOUT,
            ManeuverType.ROUNDABOUT_CW to IconId.DA_TURN_GENERIC_ROUNDABOUT_LEFT,
            ManeuverType.ROUNDABOUT_EXIT to IconId.DA_TURN_ROUNDABOUT_EXIT,
            ManeuverType.ROUNDABOUT_EXIT_CW to IconId.DA_TURN_ROUNDABOUT_EXIT_LEFT,
            ManeuverType.ROUNDABOUT_SHARP_RIGHT to IconId.DA_TURN_ROUNDABOUT_1,
            ManeuverType.ROUNDABOUT_RIGHT to IconId.DA_TURN_ROUNDABOUT_2,
            ManeuverType.ROUNDABOUT_SLIGHT_RIGHT to IconId.DA_TURN_ROUNDABOUT_3,
            ManeuverType.ROUNDABOUT_STRAIGHT to IconId.DA_TURN_ROUNDABOUT_4,
            ManeuverType.ROUNDABOUT_SLIGHT_LEFT to IconId.DA_TURN_ROUNDABOUT_5,
            ManeuverType.ROUNDABOUT_LEFT to IconId.DA_TURN_ROUNDABOUT_6,
            ManeuverType.ROUNDABOUT_SHARP_LEFT to IconId.DA_TURN_ROUNDABOUT_7,
            ManeuverType.ROUNDABOUT_UTURN to IconId.DA_TURN_ROUNDABOUT_8,
            ManeuverType.ROUNDABOUT_SHARP_RIGHT_CW to IconId.DA_TURN_ROUNDABOUT_1_LEFT,
            ManeuverType.ROUNDABOUT_RIGHT_CW to IconId.DA_TURN_ROUNDABOUT_2_LEFT,
            ManeuverType.ROUNDABOUT_SLIGHT_RIGHT_CW to IconId.DA_TURN_ROUNDABOUT_3_LEFT,
            ManeuverType.ROUNDABOUT_STRAIGHT_CW to IconId.DA_TURN_ROUNDABOUT_4_LEFT,
            ManeuverType.ROUNDABOUT_SLIGHT_LEFT_CW to IconId.DA_TURN_ROUNDABOUT_5_LEFT,
            ManeuverType.ROUNDABOUT_LEFT_CW to IconId.DA_TURN_ROUNDABOUT_6_LEFT,
            ManeuverType.ROUNDABOUT_SHARP_LEFT_CW to IconId.DA_TURN_ROUNDABOUT_7_LEFT,
            ManeuverType.ROUNDABOUT_UTURN_CW to IconId.DA_TURN_ROUNDABOUT_8_LEFT,
            ManeuverType.DESTINATION to IconId.DA_TURN_ARRIVE,
            ManeuverType.DESTINATION_LEFT to IconId.DA_TURN_ARRIVE_LEFT,
            ManeuverType.DESTINATION_RIGHT to IconId.DA_TURN_ARRIVE_RIGHT,
            ManeuverType.UNKNOWN to IconId.DA_TURN_STRAIGHT
        )

        expected.forEach { (maneuver, iconId) ->
            assertEquals("$maneuver", iconId, maneuver.toProjectionIconId())
        }
        assertEquals(ManeuverType.entries.toSet(), expected.keys)
    }
}
