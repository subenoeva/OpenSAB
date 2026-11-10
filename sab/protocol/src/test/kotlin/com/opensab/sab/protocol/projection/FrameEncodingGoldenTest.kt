package com.opensab.sab.protocol.projection

import com.opensab.sab.protocol.assignment.ColorId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameEncodingGoldenTest {

    @Test
    fun encodeGoldenVectorG1RequestSabMode() {
        val frame = ProjectionFrame.RequestSabMode(requestId = 0x05)
        val encoded = frame.encode()

        assertArrayEquals(
            byteArrayOf(0x0A, 0x05),
            encoded
        )
    }

    @Test
    fun encodeGoldenVectorG2ActiveIndicators() {
        val frame = ProjectionFrame.ActiveIndicators(
            requestId = 0x10,
            indicatorMask = 0x02 or 0x04 // NAVIGATION (0x02) | MUSIC (0x04) = 0x06
        )
        val encoded = frame.encode()

        assertArrayEquals(
            byteArrayOf(0x03, 0x10, 0x00, 0x06),
            encoded
        )
    }

    @Test
    fun encodeGoldenVectorG3DismissModal() {
        val frame = ProjectionFrame.DismissModal(requestId = 0x22)
        val encoded = frame.encode()

        assertArrayEquals(
            byteArrayOf(0x02, 0x22, 0x00, 0x00, 0x00),
            encoded
        )
    }

    @Test
    fun encodeGoldenVectorG4LiveScreenMinimal() {
        val frame = ProjectionFrame.LiveScreen(
            requestId = 0x01,
            headerBgColorId = ColorId.PURE_BLACK,
            headerText = "",
            headerTextColorId = null,
            bodyBgColorId = ColorId.PURE_BLACK,
            leftActionIconId = IconId.IC_NONE,
            leftActionIconTintColorId = null,
            leftActionContentIconId = null,
            leftActionContentIconTintColorId = null,
            leftActionBgColorId = null,
            rightActionIconId = IconId.IC_NONE,
            rightActionIconTintColorId = null,
            rightActionContentIconId = null,
            rightActionContentIconTintColorId = null,
            rightActionBgColorId = null,
            contentBgColorId = ColorId.PURE_BLACK,
            contentIconId = IconId.IC_NONE,
            contentIconTintColorId = null,
            contentTextLine1 = "",
            contentTextColorIdLine1 = null,
            contentTextLine2 = "",
            contentTextColorIdLine2 = null
        )
        val encoded = frame.encode()

        val expected = byteArrayOf(
            0x01, 0x01, 0x01, 0x00, 0x02, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00
        )
        assertEquals(12, encoded.size)
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun encodeLiveScreenThrowsWhenLeftActionInconsistent() {
        val frame = ProjectionFrame.LiveScreen(
            requestId = 0x01,
            headerBgColorId = ColorId.PURE_BLACK,
            headerText = "",
            headerTextColorId = null,
            bodyBgColorId = ColorId.PURE_BLACK,
            leftActionIconId = IconId.LIVE_ARROW_LEFT,
            leftActionIconTintColorId = ColorId.ORANGE,
            leftActionContentIconId = null, // Missing attribute!
            leftActionContentIconTintColorId = null,
            leftActionBgColorId = null,
            rightActionIconId = IconId.IC_NONE,
            rightActionIconTintColorId = null,
            rightActionContentIconId = null,
            rightActionContentIconTintColorId = null,
            rightActionBgColorId = null,
            contentBgColorId = ColorId.PURE_BLACK,
            contentIconId = IconId.IC_NONE,
            contentIconTintColorId = null,
            contentTextLine1 = "",
            contentTextColorIdLine1 = null,
            contentTextLine2 = "",
            contentTextColorIdLine2 = null
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            frame.encode()
        }
        assertTrue(exception.message?.contains("Left action requires all 4 attributes", ignoreCase = true) == true)
    }

    @Test
    fun encodeGoldenVectorG7PopUpWithOneActionButton() {
        val frame = ProjectionFrame.PopUp(
            requestId = 0x07,
            bgColorId = ColorId.PURE_BLACK,
            iconId = IconId.WEATHER_SUNNY, // 0x0A
            iconTintColorId = ColorId.NEGATIVE, // 0x02
            text = "Aviso",
            textColorId = ColorId.NEGATIVE, // 0x02
            leftActionIconId = IconId.fromId(0x15)!!, // 0x15 (DA_LANE_STUB)
            leftActionIconTintColorId = ColorId.NEGATIVE, // 0x02
            leftActionContentIconId = IconId.fromId(0x16)!!, // 0x16 (DA_LANEGUIDANCE_TICK)
            leftActionContentIconTintColorId = ColorId.NEGATIVE, // 0x02
            leftActionBgColorId = ColorId.POSITIVE, // 0x03
            rightActionIconId = IconId.IC_NONE // 0x00
        )
        val encoded = frame.encode()

        val expected = byteArrayOf(
            0x02, 0x07, 0x01, 0x0A, 0x02, 0x05,
            0x41, 0x76, 0x69, 0x73, 0x6F, 0x02,
            0x15, 0x02, 0x16, 0x02, 0x03, 0x00
        )
        assertEquals(18, encoded.size)
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun encodeGoldenVectorG8MenuScreenWithFourArrowsAndOneTextLine() {
        val frame = ProjectionFrame.MenuScreen(
            requestId = 0x08,
            headerBgColorId = ColorId.PURE_BLACK,
            headerText = "Menu",
            headerTextColorId = ColorId.NEGATIVE, // 0x02
            bodyBgColorId = ColorId.PURE_BLACK,
            upActionIconId = IconId.MENU_ARROW_LEFT, // 0x01
            upActionIconTintColorId = ColorId.NEGATIVE, // 0x02
            downActionIconId = IconId.MENU_ARROW_RIGHT, // 0x02
            downActionIconTintColorId = ColorId.NEGATIVE, // 0x02
            leftActionIconId = IconId.MENU_ARROW_UP, // 0x03
            leftActionIconTintColorId = ColorId.NEGATIVE, // 0x02
            rightActionIconId = IconId.MENU_ARROW_DOWN, // 0x04
            rightActionIconTintColorId = ColorId.NEGATIVE, // 0x02
            contentIconId = IconId.MENU_ARROW_RIGHT_BIG, // 0x05
            contentIconTintColorId = ColorId.NEGATIVE, // 0x02
            contentTextLine1 = "Opcion 1",
            contentTextColorIdLine1 = ColorId.NEGATIVE, // 0x02
            contentTextLine2 = "",
            contentTextColorIdLine2 = null
        )
        val encoded = frame.encode()

        val expected = byteArrayOf(
            0x01, 0x08, 0x01, 0x04, 0x4D, 0x65, 0x6E, 0x75, 0x02,
            0x01, 0x01, 0x01, 0x02, 0x02, 0x02, 0x03, 0x02, 0x04,
            0x02, 0x05, 0x02, 0x08, 0x4F, 0x70, 0x63, 0x69, 0x6F,
            0x6E, 0x20, 0x31, 0x02, 0x00
        )
        assertEquals(32, encoded.size)
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun encodePopUpThrowsWhenRightActionPresentWithoutLeftAction() {
        val frame = ProjectionFrame.PopUp(
            requestId = 0x01,
            bgColorId = ColorId.PURE_BLACK,
            iconId = IconId.DFU_WARNING,
            iconTintColorId = null,
            text = "Error",
            textColorId = ColorId.PURE_WHITE,
            leftActionIconId = IconId.IC_NONE,
            rightActionIconId = IconId.LIVE_ACCEPT
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            frame.encode()
        }
        assertTrue(
            "Must indicate right action requires left action",
            exception.message?.contains("right action requires left action", ignoreCase = true) == true
        )
    }

    @Test
    fun encodePopUpWithBothActionsEmitsTenByteActionBlock() {
        val frame = ProjectionFrame.PopUp(
            requestId = 0x02,
            bgColorId = ColorId.PURE_BLACK,
            iconId = IconId.INCOMING_CALL,
            iconTintColorId = null,
            text = "Call",
            textColorId = ColorId.PURE_WHITE,
            leftActionIconId = IconId.POPUP_HANGUP,
            leftActionIconTintColorId = ColorId.NEGATIVE,
            leftActionContentIconId = IconId.IC_NONE,
            leftActionContentIconTintColorId = null,
            leftActionBgColorId = ColorId.NEGATIVE,
            rightActionIconId = IconId.POPUP_ACCEPT,
            rightActionIconTintColorId = ColorId.POSITIVE,
            rightActionContentIconId = IconId.IC_NONE,
            rightActionContentIconTintColorId = null,
            rightActionBgColorId = ColorId.POSITIVE
        )
        val encoded = frame.encode()

        // Base popup without actions = cmd(1) + reqId(1) + bg(1) + icon(1) + text(1+4+1) = 10 B
        // With both actions = 10 B + 10 B = 20 B
        assertEquals(20, encoded.size)
    }

    @Test
    fun encodePopUpWithoutActionsOmitsActionBlockEntirely() {
        val frame = ProjectionFrame.PopUp(
            requestId = 0x03,
            bgColorId = ColorId.PURE_BLACK,
            iconId = IconId.DFU_WARNING,
            iconTintColorId = null,
            text = "Notice",
            textColorId = ColorId.PURE_WHITE,
            leftActionIconId = IconId.IC_NONE,
            rightActionIconId = IconId.IC_NONE
        )
        val encoded = frame.encode()

        // cmd(1) + reqId(1) + bg(1) + icon(1) + text(1+6+1) = 12 B
        assertEquals(12, encoded.size)
    }

    /**
     * Holds for every one of the five MENU icon slots: the tint byte follows its icon only when
     * that icon is present, so an inconsistent pair silently shortens or lengthens the frame and
     * shifts every field after it.
     */
    @Test
    fun encodeMenuScreenThrowsWhenAVisibleIconOmitsItsTint() {
        val frame = menuScreen(
            rightActionIconId = IconId.MENU_ARROW_RIGHT,
            rightActionIconTintColorId = null
        )

        val exception = assertThrows(IllegalArgumentException::class.java) { frame.encode() }
        assertTrue(
            "Must name the inconsistent slot, got: ${exception.message}",
            exception.message?.contains("Right action", ignoreCase = true) == true
        )
    }

    @Test
    fun encodeMenuScreenThrowsWhenAnAbsentIconCarriesATint() {
        val frame = menuScreen(
            contentIconId = IconId.IC_NONE,
            contentIconTintColorId = ColorId.PURE_WHITE
        )

        val exception = assertThrows(IllegalArgumentException::class.java) { frame.encode() }
        assertTrue(
            "Must name the inconsistent slot, got: ${exception.message}",
            exception.message?.contains("Content icon", ignoreCase = true) == true
        )
    }

    @Test
    fun encodeLiveScreenThrowsWhenTheContentIconOmitsItsTint() {
        val frame = ProjectionFrame.LiveScreen(
            requestId = 0x01,
            headerBgColorId = ColorId.PURE_BLACK,
            headerText = "",
            headerTextColorId = null,
            bodyBgColorId = ColorId.PURE_BLACK,
            leftActionIconId = IconId.IC_NONE,
            leftActionIconTintColorId = null,
            leftActionContentIconId = null,
            leftActionContentIconTintColorId = null,
            leftActionBgColorId = null,
            rightActionIconId = IconId.IC_NONE,
            rightActionIconTintColorId = null,
            rightActionContentIconId = null,
            rightActionContentIconTintColorId = null,
            rightActionBgColorId = null,
            contentBgColorId = ColorId.PURE_BLACK,
            contentIconId = IconId.NAVIGATION,
            contentIconTintColorId = null,
            contentTextLine1 = "",
            contentTextColorIdLine1 = null,
            contentTextLine2 = "",
            contentTextColorIdLine2 = null
        )

        val exception = assertThrows(IllegalArgumentException::class.java) { frame.encode() }
        assertTrue(
            "Must name the inconsistent slot, got: ${exception.message}",
            exception.message?.contains("Content icon", ignoreCase = true) == true
        )
    }

    /** A consistent MENU frame; each test overrides only the slot it puts under test. */
    private fun menuScreen(
        upActionIconId: IconId = IconId.IC_NONE,
        upActionIconTintColorId: ColorId? = null,
        downActionIconId: IconId = IconId.IC_NONE,
        downActionIconTintColorId: ColorId? = null,
        leftActionIconId: IconId = IconId.IC_NONE,
        leftActionIconTintColorId: ColorId? = null,
        rightActionIconId: IconId = IconId.IC_NONE,
        rightActionIconTintColorId: ColorId? = null,
        contentIconId: IconId = IconId.IC_NONE,
        contentIconTintColorId: ColorId? = null
    ) = ProjectionFrame.MenuScreen(
        requestId = 0x01,
        headerBgColorId = ColorId.PURE_BLACK,
        headerText = "",
        headerTextColorId = null,
        bodyBgColorId = ColorId.PURE_BLACK,
        upActionIconId = upActionIconId,
        upActionIconTintColorId = upActionIconTintColorId,
        downActionIconId = downActionIconId,
        downActionIconTintColorId = downActionIconTintColorId,
        leftActionIconId = leftActionIconId,
        leftActionIconTintColorId = leftActionIconTintColorId,
        rightActionIconId = rightActionIconId,
        rightActionIconTintColorId = rightActionIconTintColorId,
        contentIconId = contentIconId,
        contentIconTintColorId = contentIconTintColorId,
        contentTextLine1 = "",
        contentTextColorIdLine1 = null,
        contentTextLine2 = "",
        contentTextColorIdLine2 = null
    )

    @Test
    fun encodeActiveIndicatorsWithNegativeMaskExtendsSignHighByte() {
        val frame = ProjectionFrame.ActiveIndicators(
            requestId = 0x01,
            indicatorMask = -1
        )
        val encoded = frame.encode()

        assertArrayEquals(
            byteArrayOf(0x03, 0x01, 0xFF.toByte(), 0xFF.toByte()),
            encoded
        )
    }
}
