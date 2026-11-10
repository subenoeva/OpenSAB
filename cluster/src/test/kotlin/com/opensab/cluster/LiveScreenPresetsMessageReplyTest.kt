package com.opensab.cluster

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.IconId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one preset with seven faces, and what stays the same across all of them.
 *
 * `LiveScreenPresetsContentIconTest` already walks the seven for the icon rule; what is pinned here
 * is the rest of the shape, and above all the two halves of one rule: a face that answers a key
 * paints its button, and a face that answers none paints a dark block with **every** byte of it
 * cleared — an arrow with no tint shifts every field after it on the wire
 * (`FrameEncoder.requireConsistentTint`).
 */
class LiveScreenPresetsMessageReplyTest {

    @Test
    fun `puts the transcript on the first line and leaves the second empty`() {
        val frame = LiveScreenPresets.messageReply(
            requestId = 7,
            header = "Messages",
            line = "On my way",
            contentIconId = IconId.REVIEW
        )

        assertEquals("On my way", frame.contentTextLine1)
        assertEquals(ColorId.PURE_WHITE, frame.contentTextColorIdLine1)
        assertEquals("", frame.contentTextLine2)
        assertNull(frame.contentTextColorIdLine2)
    }

    @Test
    fun `paints the send button over positive`() {
        val frame = LiveScreenPresets.messageReply(
            requestId = 7,
            header = "Messages",
            line = "On my way",
            contentIconId = IconId.REVIEW,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = IconId.LIVE_SEND,
            rightActionBgColorId = ColorId.POSITIVE
        )

        assertEquals(IconId.LIVE_ARROW_RIGHT, frame.rightActionIconId)
        assertEquals(ColorId.PURE_BLACK, frame.rightActionIconTintColorId)
        assertEquals(IconId.LIVE_SEND, frame.rightActionContentIconId)
        assertEquals(ColorId.PURE_WHITE, frame.rightActionContentIconTintColorId)
        assertEquals(ColorId.POSITIVE, frame.rightActionBgColorId)
    }

    /**
     * A face that answers no key clears the whole block and not just the icon. This is the byte
     * discipline the icon/second-line rule was found through: a tint left behind an absent icon is not a
     * cosmetic slip, it moves every field after it.
     */
    @Test
    fun `clears every byte of a block it does not answer`() {
        val frame = LiveScreenPresets.messageReply(
            requestId = 7,
            header = "Messages",
            line = "Sending...",
            contentIconId = IconId.LOADING
        )

        assertEquals(IconId.IC_NONE, frame.leftActionIconId)
        assertNull(frame.leftActionIconTintColorId)
        assertNull(frame.leftActionContentIconId)
        assertNull(frame.leftActionContentIconTintColorId)
        assertNull(frame.leftActionBgColorId)
        assertEquals(IconId.IC_NONE, frame.rightActionIconId)
        assertNull(frame.rightActionIconTintColorId)
        assertNull(frame.rightActionContentIconId)
        assertNull(frame.rightActionContentIconTintColorId)
        assertNull(
            "a background behind no button is a coloured gap on the cluster",
            frame.rightActionBgColorId
        )
    }

    /** The page is blue like the two it sits behind: it is the same feature, on its third screen. */
    @Test
    fun `is shaded from the message feature triple`() {
        val frame = LiveScreenPresets.messageReply(7, "Messages", "Sent", IconId.SEND)

        assertEquals("Messages", frame.headerText)
        assertEquals(ColorId.BLUE, frame.headerBgColorId)
        assertEquals(ColorId.BLUE, frame.bodyBgColorId)
        assertEquals(ColorId.BLUE_DARK, frame.contentBgColorId)
    }
}
