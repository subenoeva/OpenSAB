package com.opensab.sab.protocol.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IconIdTest {

    @Test
    fun canonicalIdsMatchSpecAEnumCatalog() {
        assertEquals(0, IconId.IC_NONE.id)

        // Menu arrows
        assertEquals(1, IconId.MENU_ARROW_LEFT.id)
        assertEquals(2, IconId.MENU_ARROW_RIGHT.id)
        assertEquals(3, IconId.MENU_ARROW_UP.id)
        assertEquals(4, IconId.MENU_ARROW_DOWN.id)
        assertEquals(5, IconId.MENU_ARROW_RIGHT_BIG.id)

        // Weather
        assertEquals(10, IconId.WEATHER_SUNNY.id)
        assertEquals(17, IconId.WEATHER_FOG.id)
        assertEquals(90, IconId.FORECAST.id)
        assertEquals(91, IconId.NO_WEATHER.id)

        // Lane guidance
        assertEquals(18, IconId.DA_LANE_DOTS.id)
        assertEquals(30, IconId.DA_LANE_UTURN.id)
        assertEquals(31, IconId.DA_LANE_NORMAL_SHORT_LEFT.id)
        assertEquals(38, IconId.DA_LANE_UTURN_LEFT.id)

        // Navigation maneuvers
        assertEquals(39, IconId.DA_TURN_STRAIGHT.id)
        assertEquals(44, IconId.DA_TURN_RIGHT.id)
        assertEquals(53, IconId.DA_TURN_LEFT.id)
        assertEquals(62, IconId.DA_TURN_ROUNDABOUT_1.id)
        assertEquals(69, IconId.DA_TURN_ROUNDABOUT_8.id)
        assertEquals(70, IconId.DA_TURN_ROUNDABOUT_1_LEFT.id)
        assertEquals(77, IconId.DA_TURN_ROUNDABOUT_8_LEFT.id)

        // Telephony, PDI, DFU, Volume
        assertEquals(86, IconId.DFU_WARNING.id)
        assertEquals(94, IconId.ACTIVE_MUSIC.id)
        assertEquals(107, IconId.INCOMING_CALL.id)
        assertEquals(111, IconId.MUSIC_PLAYER.id)
        assertEquals(117, IconId.VOLUME_STATIC.id)
        assertEquals(123, IconId.VOLUME_LEVEL_5.id)
    }

    @Test
    fun fromIdResolvesValidAndInvalidCodes() {
        assertEquals(IconId.IC_NONE, IconId.fromId(0))
        assertNotNull(IconId.fromId(44))
        assertNull(IconId.fromId(999))
    }
}
