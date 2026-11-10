package com.opensab.sab.protocol.projection

import com.opensab.sab.protocol.assignment.ColorId

sealed interface ProjectionFrame {

    val commandId: CommandId
    val requestId: Int

    fun encode(): ByteArray

    /** Dynamic state card (command 0x01, body type 0x02). */
    data class LiveScreen(
        override val requestId: Int,
        val headerBgColorId: ColorId,
        val headerText: String?,
        val headerTextColorId: ColorId?,
        val bodyBgColorId: ColorId,
        val leftActionIconId: IconId,
        val leftActionIconTintColorId: ColorId?,
        val leftActionContentIconId: IconId?,
        val leftActionContentIconTintColorId: ColorId?,
        val leftActionBgColorId: ColorId?,
        val rightActionIconId: IconId,
        val rightActionIconTintColorId: ColorId?,
        val rightActionContentIconId: IconId?,
        val rightActionContentIconTintColorId: ColorId?,
        val rightActionBgColorId: ColorId?,
        val contentBgColorId: ColorId,
        val contentIconId: IconId,
        val contentIconTintColorId: ColorId?,
        val contentTextLine1: String?,
        val contentTextColorIdLine1: ColorId?,
        val contentTextLine2: String?,
        val contentTextColorIdLine2: ColorId?
    ) : ProjectionFrame {
        override val commandId: CommandId = CommandId.MYSTERY_BOX
        override fun encode(): ByteArray = FrameEncoder.encodeLiveScreen(this)
    }

    /** Navigable menu screen (command 0x01, body type 0x01). */
    data class MenuScreen(
        override val requestId: Int,
        val headerBgColorId: ColorId,
        val headerText: String?,
        val headerTextColorId: ColorId?,
        val bodyBgColorId: ColorId,
        val upActionIconId: IconId,
        val upActionIconTintColorId: ColorId?,
        val downActionIconId: IconId,
        val downActionIconTintColorId: ColorId?,
        val leftActionIconId: IconId,
        val leftActionIconTintColorId: ColorId?,
        val rightActionIconId: IconId,
        val rightActionIconTintColorId: ColorId?,
        val contentIconId: IconId,
        val contentIconTintColorId: ColorId?,
        val contentTextLine1: String?,
        val contentTextColorIdLine1: ColorId?,
        val contentTextLine2: String?,
        val contentTextColorIdLine2: ColorId?
    ) : ProjectionFrame {
        override val commandId: CommandId = CommandId.MYSTERY_BOX
        override fun encode(): ByteArray = FrameEncoder.encodeMenuScreen(this)
    }

    /** High-priority modal popup (command 0x02). */
    data class PopUp(
        override val requestId: Int,
        val bgColorId: ColorId,
        val iconId: IconId,
        val iconTintColorId: ColorId?,
        val text: String?,
        val textColorId: ColorId?,
        val leftActionIconId: IconId = IconId.IC_NONE,
        val leftActionIconTintColorId: ColorId? = null,
        val leftActionContentIconId: IconId? = null,
        val leftActionContentIconTintColorId: ColorId? = null,
        val leftActionBgColorId: ColorId? = null,
        val rightActionIconId: IconId = IconId.IC_NONE,
        val rightActionIconTintColorId: ColorId? = null,
        val rightActionContentIconId: IconId? = null,
        val rightActionContentIconTintColorId: ColorId? = null,
        val rightActionBgColorId: ColorId? = null
    ) : ProjectionFrame {
        override val commandId: CommandId = CommandId.POP_UP
        override fun encode(): ByteArray = FrameEncoder.encodePopUp(this)
    }

    /** Dismisses the active popup: a POP_UP zeroed after the request id. */
    data class DismissModal(
        override val requestId: Int
    ) : ProjectionFrame {
        override val commandId: CommandId = CommandId.POP_UP
        override fun encode(): ByteArray = byteArrayOf(
            0x02,
            (requestId and 0xFF).toByte(),
            0x00,
            0x00,
            0x00
        )
    }

    /** Sets the permanent indicator icons on the cluster (command 0x03). */
    data class ActiveIndicators(
        override val requestId: Int,
        val indicatorMask: Int
    ) : ProjectionFrame {
        override val commandId: CommandId = CommandId.ACTIVE_INDICATORS_CONTROL
        override fun encode(): ByteArray = FrameEncoder.encodeActiveIndicators(this)
    }

    /**
     * Asks the cluster to switch to SAB mode (command 0x0A). The verdict arrives as a 0x09
     * notification, never in this request's receipt.
     */
    data class RequestSabMode(
        override val requestId: Int
    ) : ProjectionFrame {
        override val commandId: CommandId = CommandId.REQUEST_SAB_MODE
        override fun encode(): ByteArray = FrameEncoder.encodeRequestSabMode(this)
    }

    /** Firmware update status (command 0x0B). */
    data class FirmwareUpdate(
        override val requestId: Int,
        val status: Int
    ) : ProjectionFrame {
        override val commandId: CommandId = CommandId.FIRMWARE_UPDATE
        override fun encode(): ByteArray = FrameEncoder.encodeFirmwareUpdate(this)
    }
}
