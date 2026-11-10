package com.opensab.cluster

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.IconId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveScreenPresetsTest {

    @Test
    fun `splitDestinationText should keep short query on line1`() {
        val (line1, line2) = LiveScreenPresets.splitDestinationText("Repsol Castellana")
        assertEquals("Repsol Castellana", line1)
        assertEquals("", line2)
    }

    @Test
    fun `splitDestinationText should split long query respecting word boundaries and 30 byte budget`() {
        val (line1, line2) = LiveScreenPresets.splitDestinationText("Gasolinera Repsol Paseo de la Castellana 120 Madrid")
        assertTrue("line1 bytes must be <= 30", line1.toByteArray(Charsets.UTF_8).size <= 30)
        assertTrue("line2 bytes must be <= 30", line2.toByteArray(Charsets.UTF_8).size <= 30)
        assertEquals("Gasolinera Repsol Paseo de la", line1)
        assertEquals("Castellana 120 Madrid", line2)
    }

    @Test
    fun `splitDestinationText should handle multibyte utf-8 characters safely without corrupting code points`() {
        val (line1, line2) = LiveScreenPresets.splitDestinationText("Calle Alcalá, 42 Paseo Constitución de Madrid")
        assertTrue("line1 bytes must be <= 30", line1.toByteArray(Charsets.UTF_8).size <= 30)
        assertTrue("line2 bytes must be <= 30", line2.toByteArray(Charsets.UTF_8).size <= 30)
        assertEquals(line1, String(line1.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertEquals(line2, String(line2.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `splitDestinationText should handle empty or blank query`() {
        assertEquals(Pair("", ""), LiveScreenPresets.splitDestinationText(""))
        assertEquals(Pair("", ""), LiveScreenPresets.splitDestinationText("   "))
    }

    @Test
    fun `splitDestinationText should truncate first word safely when it exceeds 30 bytes alone`() {
        val longWord = "Supercalifragilisticoespialidoso12345"
        val (line1, line2) = LiveScreenPresets.splitDestinationText(longWord)
        assertTrue("line1 bytes must be <= 30", line1.toByteArray(Charsets.UTF_8).size <= 30)
        assertTrue("line2 bytes must be <= 30", line2.toByteArray(Charsets.UTF_8).size <= 30)
        assertEquals(longWord.substring(0, 30), line1)
        assertEquals(longWord.substring(30), line2)
    }

    @Test
    fun `navVoice should configure green theme and suppress content icon when line2 is present`() {
        val frame = LiveScreenPresets.navVoice(
            requestId = 1,
            header = "Nav",
            line1 = "Repsol",
            line2 = "Paseo Castellana",
            contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = IconId.LIVE_ACCEPT,
            rightActionBgColor = ColorId.POSITIVE
        )

        assertEquals(ColorId.GREEN, frame.headerBgColorId)
        assertEquals(ColorId.GREEN, frame.bodyBgColorId)
        assertEquals(ColorId.GREEN_DARK, frame.contentBgColorId)
        assertEquals(IconId.IC_NONE, frame.contentIconId) // line2 suppresses content icon
        assertNull(frame.contentIconTintColorId)
        assertEquals(IconId.LIVE_ARROW_LEFT, frame.leftActionIconId)
        assertEquals(ColorId.PURE_BLACK, frame.leftActionIconTintColorId)
        assertEquals(IconId.LIVE_RETRY, frame.leftActionContentIconId)
        assertEquals(ColorId.PURE_WHITE, frame.leftActionContentIconTintColorId)
        assertEquals(ColorId.GREEN_LIGHT, frame.leftActionBgColorId)
        assertEquals(IconId.LIVE_ARROW_RIGHT, frame.rightActionIconId)
        assertEquals(ColorId.PURE_BLACK, frame.rightActionIconTintColorId)
        assertEquals(IconId.LIVE_ACCEPT, frame.rightActionContentIconId)
        assertEquals(ColorId.PURE_WHITE, frame.rightActionContentIconTintColorId)
        assertEquals(ColorId.POSITIVE, frame.rightActionBgColorId)
        assertEquals("Repsol", frame.contentTextLine1)
        assertEquals(ColorId.PURE_WHITE, frame.contentTextColorIdLine1)
        assertEquals("Paseo Castellana", frame.contentTextLine2)
        assertEquals(ColorId.PURE_WHITE, frame.contentTextColorIdLine2)
    }

    @Test
    fun `navVoice should preserve content icon and clear actions when line2 is empty and actions omitted`() {
        val frame = LiveScreenPresets.navVoice(
            requestId = 1,
            header = "Nav",
            line1 = "Listening...",
            line2 = "",
            contentIconId = IconId.MIC
        )

        assertEquals(ColorId.GREEN, frame.headerBgColorId)
        assertEquals(ColorId.GREEN, frame.bodyBgColorId)
        assertEquals(ColorId.GREEN_DARK, frame.contentBgColorId)
        assertEquals(IconId.MIC, frame.contentIconId)
        assertEquals(ColorId.PURE_WHITE, frame.contentIconTintColorId)
        assertEquals(IconId.IC_NONE, frame.leftActionIconId)
        assertNull(frame.leftActionIconTintColorId)
        assertNull(frame.leftActionContentIconId)
        assertNull(frame.leftActionContentIconTintColorId)
        assertNull(frame.leftActionBgColorId)
        assertEquals(IconId.IC_NONE, frame.rightActionIconId)
        assertNull(frame.rightActionIconTintColorId)
        assertNull(frame.rightActionContentIconId)
        assertNull(frame.rightActionContentIconTintColorId)
        assertNull(frame.rightActionBgColorId)
        assertEquals("Listening...", frame.contentTextLine1)
        assertEquals(ColorId.PURE_WHITE, frame.contentTextColorIdLine1)
        assertEquals("", frame.contentTextLine2)
        assertNull(frame.contentTextColorIdLine2)
    }

    @Test
    fun `should paint the dialler in the purple of the phone entry`() {
        val frame = LiveScreenPresets.callDial(1, "Phone", "Say a name", contentIconId = IconId.MIC)

        assertEquals("Phone", frame.headerText)
        assertEquals(ColorId.PURPLE, frame.headerBgColorId)
        assertEquals(ColorId.PURPLE, frame.bodyBgColorId)
        assertEquals(ColorId.PURPLE_DARK, frame.contentBgColorId)
    }

    @Test
    fun `should drop the content icon when there is a number kind to show`() {
        val confirming = LiveScreenPresets.callDial(
            requestId = 1,
            header = "Phone",
            line1 = "Ana Ruiz",
            line2 = "Mobile",
            contentIconId = IconId.CONTACT_RESULT,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = IconId.LIVE_CALL,
            rightActionBgColorId = ColorId.POSITIVE
        )

        // The BTU rejects icon and second line together with status=0x0C, and the presets are the
        // one place the rule is kept.
        assertEquals(IconId.IC_NONE, confirming.contentIconId)
        assertNull(confirming.contentIconTintColorId)
        assertEquals("Ana Ruiz", confirming.contentTextLine1)
        assertEquals("Mobile", confirming.contentTextLine2)
    }

    @Test
    fun `should paint the call button green only where a press places a call`() {
        val frame = LiveScreenPresets.callDial(
            requestId = 1,
            header = "Phone",
            line1 = "Ana Ruiz",
            line2 = "Mobile",
            contentIconId = IconId.IC_NONE,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = IconId.LIVE_CALL,
            rightActionBgColorId = ColorId.POSITIVE
        )

        assertEquals(IconId.LIVE_ARROW_RIGHT, frame.rightActionIconId)
        assertEquals(ColorId.PURE_BLACK, frame.rightActionIconTintColorId)
        assertEquals(IconId.LIVE_CALL, frame.rightActionContentIconId)
        assertEquals(ColorId.PURE_WHITE, frame.rightActionContentIconTintColorId)
        assertEquals(ColorId.POSITIVE, frame.rightActionBgColorId)
    }

    @Test
    fun `should keep a block dark when the face answers no key`() {
        val frame = LiveScreenPresets.callDial(1, "Phone", "Calling...", contentIconId = IconId.LOADING)

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

    /**
     * The presets format; they do not write. A header the caller did not choose is a screen this
     * layer decided the language of, and this layer is the one whose bytes are verified against a
     * BTU 0536 with golden vectors.
     */
    @Test
    fun `should take the header of every live screen from its caller`() {
        val header = "HEADER"

        val headers = listOf(
            LiveScreenPresets.music(1, header, "t", "a", playing = true),
            LiveScreenPresets.navigation(1, header, "in", "st", IconId.NAVIGATION),
            LiveScreenPresets.volume(1, header, "MUTED", percent = 42, isMuted = false),
            LiveScreenPresets.call(1, header, "Ana", "STATUS"),
            LiveScreenPresets.callRinging(1, header, "Ana", "STATUS"),
            LiveScreenPresets.callDial(1, header, "line"),
            LiveScreenPresets.weather(1, header, "20C Clear", "Vigo"),
            LiveScreenPresets.message(1, header, "Ana", "hi"),
            LiveScreenPresets.messagePrompt(1, header, "Ana", "WhatsApp"),
            LiveScreenPresets.messageReply(1, header, "line", IconId.MIC)
        ).map { it.headerText }

        assertEquals(List(10) { header }, headers)
    }

    /**
     * A standby carries two: the word for the page and the line that says there is nothing on it.
     * `idle` is the exception and stays as it is — its header and both its lines are empty, and an
     * empty string is an absence, not copy.
     */
    @Test
    fun `should take both lines of every standby from its caller`() {
        val standbys = listOf(
            LiveScreenPresets.navigationStandby(1, "HEADER", "EMPTY"),
            LiveScreenPresets.callStandby(1, "HEADER", "EMPTY"),
            LiveScreenPresets.weatherStandby(1, "HEADER", "EMPTY"),
            LiveScreenPresets.messageStandby(1, "HEADER", "EMPTY")
        )

        assertEquals(List(4) { "HEADER" }, standbys.map { it.headerText })
        assertEquals(List(4) { "EMPTY" }, standbys.map { it.contentTextLine1 })
    }

    /**
     * The arrow is the promise of which button moves the level, and on this handlebar the level
     * moves with up and down: the rider reported the raise block wearing a left arrow and the lower
     * block a right one, on a bike whose cross has no left/right volume at all.
     *
     * The blocks keep the sides and the content icons they already had — those are the bytes the
     * BTU 0536 accepted — and only the arrows turn.
     */
    @Test
    fun `should point the volume arrows the way the rider's thumb moves`() {
        val frame = LiveScreenPresets.volume(1, "Volume", "Mute", percent = 42, isMuted = false)

        assertEquals(IconId.LIVE_ARROW_UP, frame.leftActionIconId)
        assertEquals(IconId.LIVE_VOLUME_DOWN, frame.leftActionContentIconId)
        assertEquals(IconId.LIVE_ARROW_DOWN, frame.rightActionIconId)
        assertEquals(IconId.LIVE_VOLUME_UP, frame.rightActionContentIconId)
    }

    /**
     * `"$percent%"` is not copy: it is a number and a sign, and it reads the same in both languages.
     * The word next to it is copy, and that one leaves.
     */
    @Test
    fun `should keep the percentage and leave the mute word to its caller`() {
        assertEquals(
            "42%",
            LiveScreenPresets.volume(1, "HEADER", "MUTED", percent = 42, isMuted = false)
                .contentTextLine1
        )
        assertEquals(
            "MUTED",
            LiveScreenPresets.volume(1, "HEADER", "MUTED", percent = 42, isMuted = true)
                .contentTextLine1
        )
    }

    @Test
    fun `weather should configure action buttons with cancel and retry`() {
        val frame = LiveScreenPresets.weather(
            requestId = 1,
            header = "Weather",
            temperatureAndCondition = "20°C Clear",
            location = "Vigo"
        )

        assertEquals(IconId.LIVE_ARROW_LEFT, frame.leftActionIconId)
        assertEquals(ColorId.PURE_BLACK, frame.leftActionIconTintColorId)
        assertEquals(IconId.LIVE_CANCEL_TWO_BUTTONS, frame.leftActionContentIconId)
        assertEquals(ColorId.PURE_WHITE, frame.leftActionContentIconTintColorId)
        assertEquals(ColorId.GREY_LIGHT, frame.leftActionBgColorId)

        assertEquals(IconId.LIVE_ARROW_RIGHT, frame.rightActionIconId)
        assertEquals(ColorId.PURE_BLACK, frame.rightActionIconTintColorId)
        assertEquals(IconId.LIVE_RETRY, frame.rightActionContentIconId)
        assertEquals(ColorId.PURE_WHITE, frame.rightActionContentIconTintColorId)
        assertEquals(ColorId.GREY_LIGHT, frame.rightActionBgColorId)
    }

    @Test
    fun `musicLoading should configure orange theme with loading icon and empty actions and line2`() {
        val frame = LiveScreenPresets.musicLoading(1, "Loading")

        assertEquals(ColorId.ORANGE, frame.headerBgColorId)
        assertEquals(ColorId.ORANGE, frame.bodyBgColorId)
        assertEquals(ColorId.ORANGE_DARK, frame.contentBgColorId)
        assertEquals("Loading", frame.headerText)
        assertEquals(ColorId.PURE_WHITE, frame.headerTextColorId)
        assertEquals(IconId.LOADING, frame.contentIconId)
        assertEquals(ColorId.PURE_WHITE, frame.contentIconTintColorId)
        assertEquals(IconId.IC_NONE, frame.leftActionIconId)
        assertNull(frame.leftActionBgColorId)
        assertEquals(IconId.IC_NONE, frame.rightActionIconId)
        assertNull(frame.rightActionBgColorId)
        assertEquals("", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun `musicError should configure orange theme with dfu warning and left cancel button`() {
        val frame = LiveScreenPresets.musicError(1, "App Error")

        assertEquals(ColorId.ORANGE, frame.headerBgColorId)
        assertEquals(ColorId.ORANGE, frame.bodyBgColorId)
        assertEquals(ColorId.ORANGE_DARK, frame.contentBgColorId)
        assertEquals("App Error", frame.headerText)
        assertEquals(ColorId.PURE_WHITE, frame.headerTextColorId)
        assertEquals(IconId.DFU_WARNING, frame.contentIconId)
        assertEquals(ColorId.PURE_WHITE, frame.contentIconTintColorId)
        assertEquals(IconId.LIVE_ARROW_LEFT, frame.leftActionIconId)
        assertEquals(ColorId.PURE_BLACK, frame.leftActionIconTintColorId)
        assertEquals(IconId.LIVE_CANCEL_ONE_BUTTON, frame.leftActionContentIconId)
        assertEquals(ColorId.PURE_WHITE, frame.leftActionContentIconTintColorId)
        assertEquals(ColorId.ORANGE_LIGHT, frame.leftActionBgColorId)
        assertEquals(IconId.IC_NONE, frame.rightActionIconId)
        assertNull(frame.rightActionBgColorId)
        assertEquals("", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }
}


