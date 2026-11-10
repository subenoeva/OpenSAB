package com.opensab.cluster

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.IconId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The frame of the page that asks before reading.
 *
 * Two things separate it from [LiveScreenPresets.message], and both are the same rule read the two
 * ways round: **the frame does not promise what the key does not do, and does not hide what it
 * does**. The read page keeps its right button dark because its `SELECT` returns `None`; this page
 * paints `LIVE_PLAY` because its `SELECT` opens the read page.
 */
class LiveScreenPresetsMessagePromptTest {

    @Test
    fun `should paint sender and app name on the two content lines`() {
        val screen = LiveScreenPresets.messagePrompt(7, "Messages", sender = "Carlos", appName = "WhatsApp")

        assertEquals(7, screen.requestId)
        assertEquals("Carlos", screen.contentTextLine1)
        assertEquals("WhatsApp", screen.contentTextLine2)
        assertEquals(ColorId.PURE_WHITE, screen.contentTextColorIdLine1)
        assertEquals(ColorId.PURE_WHITE, screen.contentTextColorIdLine2)
    }

    @Test
    fun `should carry no content icon while the app name fills line 2`() {
        val screen = LiveScreenPresets.messagePrompt(1, "Messages", sender = "Carlos", appName = "WhatsApp")

        assertEquals(IconId.IC_NONE, screen.contentIconId)
        assertNull(screen.contentIconTintColorId)
    }

    @Test
    fun `should fall back to the message icon when the app name is empty`() {
        val screen = LiveScreenPresets.messagePrompt(1, "Messages", sender = "Carlos", appName = "")

        assertEquals(IconId.MESSAGE, screen.contentIconId)
        assertEquals(ColorId.PURE_WHITE, screen.contentIconTintColorId)
        assertEquals("", screen.contentTextLine2)
        assertNull(screen.contentTextColorIdLine2)
    }

    @Test
    fun `should paint the play button because select does something`() {
        val screen = LiveScreenPresets.messagePrompt(1, "Messages", sender = "Carlos", appName = "WhatsApp")

        assertEquals(IconId.LIVE_ARROW_RIGHT, screen.rightActionIconId)
        assertEquals(ColorId.PURE_BLACK, screen.rightActionIconTintColorId)
        assertEquals(IconId.LIVE_PLAY, screen.rightActionContentIconId)
        assertEquals(ColorId.PURE_WHITE, screen.rightActionContentIconTintColorId)
        assertEquals(ColorId.POSITIVE, screen.rightActionBgColorId)
    }

    @Test
    fun `should paint the ignore button on the left like the read page does`() {
        val screen = LiveScreenPresets.messagePrompt(1, "Messages", sender = "Carlos", appName = "WhatsApp")

        assertEquals(IconId.LIVE_ARROW_LEFT, screen.leftActionIconId)
        assertEquals(ColorId.PURE_BLACK, screen.leftActionIconTintColorId)
        assertEquals(IconId.LIVE_CANCEL_TWO_BUTTONS, screen.leftActionContentIconId)
        assertEquals(ColorId.PURE_WHITE, screen.leftActionContentIconTintColorId)
        assertEquals(ColorId.BLUE_LIGHT, screen.leftActionBgColorId)
    }

    @Test
    fun `should paint the feature triple of the messaging colours`() {
        val screen = LiveScreenPresets.messagePrompt(1, "Messages", sender = "Carlos", appName = "WhatsApp")

        assertEquals("Messages", screen.headerText)
        assertEquals(ColorId.PURE_WHITE, screen.headerTextColorId)
        assertEquals(ColorId.BLUE, screen.headerBgColorId)
        assertEquals(ColorId.BLUE, screen.bodyBgColorId)
        assertEquals(ColorId.BLUE_DARK, screen.contentBgColorId)
    }

    @Test
    fun `should fall back to Messages when the sender is empty`() {
        val screen = LiveScreenPresets.messagePrompt(1, "Messages", sender = "", appName = "WhatsApp")

        assertEquals("Messages", screen.contentTextLine1)
    }
}
