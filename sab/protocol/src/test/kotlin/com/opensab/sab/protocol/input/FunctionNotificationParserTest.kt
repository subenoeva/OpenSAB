package com.opensab.sab.protocol.input

import com.opensab.sab.protocol.assignment.FunctionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionNotificationParserTest {

    @Test
    fun parseButton1To8ReturnsButtonPressedEvent() {
        val buttonMap = mapOf(
            1 to FunctionId.PREVIOUS,
            2 to FunctionId.NEXT,
            3 to FunctionId.BACK,
            4 to FunctionId.SELECT,
            5 to FunctionId.VOLUME_UP,
            6 to FunctionId.VOLUME_DOWN,
            7 to FunctionId.VOLUME_MUTE,
            8 to FunctionId.MENU
        )

        for ((code, expectedFunc) in buttonMap) {
            val event = FunctionNotificationParser.parse(byteArrayOf(code.toByte()))
            assertNotNull("Expected non-null event for code $code", event)
            assertTrue(event is HandlebarEvent.ButtonPressed)
            assertEquals(expectedFunc, (event as HandlebarEvent.ButtonPressed).functionId)
        }
    }

    @Test
    fun parseMode9And10ReturnsModeChangedEvent() {
        val sabEvent = FunctionNotificationParser.parse(byteArrayOf(0x09))
        assertNotNull(sabEvent)
        assertTrue(sabEvent is HandlebarEvent.ModeChanged)
        assertEquals(DashboardMode.SAB_MODE, (sabEvent as HandlebarEvent.ModeChanged).mode)

        val huEvent = FunctionNotificationParser.parse(byteArrayOf(0x0A))
        assertNotNull(huEvent)
        assertTrue(huEvent is HandlebarEvent.ModeChanged)
        assertEquals(DashboardMode.HU_MODE, (huEvent as HandlebarEvent.ModeChanged).mode)
    }

    @Test
    fun parseInvalidOrEmptyPayloadReturnsNull() {
        assertNull(FunctionNotificationParser.parse(null))
        assertNull(FunctionNotificationParser.parse(byteArrayOf()))
        assertNull(FunctionNotificationParser.parse(byteArrayOf(0x00)))
        assertNull(FunctionNotificationParser.parse(byteArrayOf(0x0B)))
        assertNull(FunctionNotificationParser.parse(byteArrayOf(0xFF.toByte())))
    }
}
