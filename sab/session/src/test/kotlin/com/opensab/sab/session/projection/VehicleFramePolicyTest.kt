package com.opensab.sab.session.projection

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.session.vehicle.VehicleCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleFramePolicyTest {

    private val policy = VehicleFramePolicy(AsciiFoldingTextSanitizer())

    private val segments = VehicleCapabilities(segmentDisplay = true)

    private val colour = VehicleCapabilities.Permissive

    @Test
    fun `folds every background to pure black on a segment cluster`() {
        val frame = TestFrames.music(1, "Music", "Title", "Artist", playing = true)

        val adapted = policy.adapt(frame, segments) as ProjectionFrame.LiveScreen

        assertEquals(ColorId.PURE_BLACK, adapted.headerBgColorId)
        assertEquals(ColorId.PURE_BLACK, adapted.bodyBgColorId)
        assertEquals(ColorId.PURE_BLACK, adapted.contentBgColorId)
    }

    @Test
    fun `folds every foreground to pure white on a segment cluster`() {
        val frame = TestFrames.music(1, "Music", "Title", "Artist", playing = true)

        val adapted = policy.adapt(frame, segments) as ProjectionFrame.LiveScreen

        assertEquals(ColorId.PURE_WHITE, adapted.contentTextColorIdLine1)
        // GREY_LIGHT in the preset. Folding it by luminance would send it to black on a black
        // background and the artist line would vanish; role decides, not brightness.
        assertEquals(ColorId.PURE_WHITE, adapted.contentTextColorIdLine2)
        assertEquals(ColorId.PURE_WHITE, adapted.leftActionContentIconTintColorId)
    }

    @Test
    fun `preserves an absent optional colour instead of inventing one`() {
        val frame = TestFrames.idle(1)

        val adapted = policy.adapt(frame, segments) as ProjectionFrame.LiveScreen

        // putIdOrOmit makes an absent optional disappear from the buffer entirely, so turning a
        // null into PURE_WHITE would shift every field after it and the BTU would reject the frame.
        assertNull(adapted.leftActionBgColorId)
        assertNull(adapted.rightActionContentIconTintColorId)
    }

    @Test
    fun `leaves a colour panel frame byte for byte identical`() {
        val frame = TestFrames.music(7, "Music", "Café Tacvba", "Ingrata", playing = false)

        val adapted = policy.adapt(frame, colour)

        assertEquals(frame, adapted)
    }

    @Test
    fun `sanitises every text field of a live screen on a segment cluster`() {
        val frame = TestFrames.music(1, "Music", "Café Tacvba", "Ingratá", playing = true)

        val adapted = policy.adapt(frame, segments) as ProjectionFrame.LiveScreen

        assertEquals("Cafe Tacvba", adapted.contentTextLine1)
        assertEquals("Ingrata", adapted.contentTextLine2)
    }

    @Test
    fun `nulls the colour when text is empty or absent`() {
        val frame = TestFrames.idle(1)

        val adapted = policy.adapt(frame, segments) as ProjectionFrame.LiveScreen

        assertEquals("", adapted.contentTextLine1)
        assertNull(adapted.contentTextColorIdLine1)
        assertEquals("", adapted.contentTextLine2)
        assertNull(adapted.contentTextColorIdLine2)
    }

    @Test
    fun `an idle frame still encodes instead of throwing`() {
        val frame = TestFrames.idle(1)

        val adapted = policy.adapt(frame, segments)

        // putTlvText requires the ColorId to be absent exactly when the text is. Without pairing
        // the two, an empty string with a color blows up the encoder mid-render.
        adapted.encode()
    }

    @Test
    fun `folds a menu screen the same way`() {
        val frame = ProjectionFrame.MenuScreen(
            requestId = 3,
            headerBgColorId = ColorId.BLUE_DARK,
            headerText = "Menú",
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.GREY_DARK,
            upActionIconId = IconId.IC_NONE,
            upActionIconTintColorId = ColorId.ORANGE_LIGHT,
            downActionIconId = IconId.IC_NONE,
            downActionIconTintColorId = null,
            leftActionIconId = IconId.IC_NONE,
            leftActionIconTintColorId = null,
            rightActionIconId = IconId.IC_NONE,
            rightActionIconTintColorId = null,
            contentIconId = IconId.IC_NONE,
            contentIconTintColorId = null,
            contentTextLine1 = "Música",
            contentTextColorIdLine1 = ColorId.GREEN_LIGHT,
            contentTextLine2 = null,
            contentTextColorIdLine2 = null
        )

        val adapted = policy.adapt(frame, segments) as ProjectionFrame.MenuScreen

        assertEquals("Menu", adapted.headerText)
        assertEquals("Musica", adapted.contentTextLine1)
        assertEquals(ColorId.PURE_BLACK, adapted.headerBgColorId)
        assertEquals(ColorId.PURE_BLACK, adapted.bodyBgColorId)
        assertEquals(ColorId.PURE_WHITE, adapted.upActionIconTintColorId)
        assertEquals(ColorId.PURE_WHITE, adapted.contentTextColorIdLine1)
        assertNull(adapted.downActionIconTintColorId)
    }

    @Test
    fun `folds a popup screen to monochrome and ascii`() {
        val frame = ProjectionFrame.PopUp(
            requestId = 4,
            bgColorId = ColorId.BLUE_DARK,
            iconId = IconId.INCOMING_CALL,
            iconTintColorId = ColorId.GREEN_LIGHT,
            text = "Llamada de José",
            textColorId = ColorId.PURE_WHITE
        )

        val adapted = policy.adapt(frame, segments) as ProjectionFrame.PopUp

        assertEquals("Llamada de Jose", adapted.text)
        assertEquals(ColorId.PURE_BLACK, adapted.bgColorId)
        assertEquals(ColorId.PURE_WHITE, adapted.iconTintColorId)
        assertEquals(ColorId.PURE_WHITE, adapted.textColorId)
    }

    @Test
    fun `leaves control packets alone`() {
        val indicators = ProjectionFrame.ActiveIndicators(requestId = 9, indicatorMask = 0x05)
        val sabMode = ProjectionFrame.RequestSabMode(requestId = 10)
        val dismiss = ProjectionFrame.DismissModal(requestId = 11)
        val fw = ProjectionFrame.FirmwareUpdate(requestId = 12, status = 1)

        assertEquals(indicators, policy.adapt(indicators, segments))
        assertEquals(sabMode, policy.adapt(sabMode, segments))
        assertEquals(dismiss, policy.adapt(dismiss, segments))
        assertEquals(fw, policy.adapt(fw, segments))
    }

    @Test
    fun `sanitizeMessage folds popup text for a segment cluster only`() {
        assertEquals("Cafe", policy.sanitizeMessage("Café", segments))
        assertEquals("Café", policy.sanitizeMessage("Café", colour))
    }

    @Test
    fun `hands the frame's text to the sanitizer with the cluster's segment flag`() {
        val observedFlags = mutableListOf<Boolean>()
        val customSanitizer = TextSanitizer { text, segmentDisplay ->
            observedFlags.add(segmentDisplay)
            text
        }
        val customPolicy = VehicleFramePolicy(customSanitizer)
        val frame = TestFrames.music(1, "Music", "Café Tacvba", "Ingratá", playing = true)

        customPolicy.adapt(frame, segments)

        assertEquals(listOf(true, true, true), observedFlags)
    }
}
