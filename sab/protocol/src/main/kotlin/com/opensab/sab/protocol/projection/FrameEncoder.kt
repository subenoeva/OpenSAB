package com.opensab.sab.protocol.projection

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.ProjectionBuffer.putIdOrOmit
import com.opensab.sab.protocol.projection.ProjectionBuffer.putIdOrZero
import com.opensab.sab.protocol.projection.ProjectionBuffer.putTlvText
import java.io.ByteArrayOutputStream

object FrameEncoder {

    /**
     * An omittable tint reaches the wire only when its icon is present, so the presence of the
     * icon *is* what tells the BTU whether the next byte is a tint. An inconsistent pair silently
     * shortens or lengthens the frame and shifts every field after it, which the BTU reports as
     * `INVALID_PARAMETERS` at whatever offset the shift finally lands on.
     */
    private fun requireConsistentTint(slot: String, iconId: IconId, tintColorId: ColorId?) {
        require((iconId != IconId.IC_NONE) == (tintColorId != null)) {
            "$slot icon id and its tint colour id are inconsistent: " +
                "the tint must be present exactly when the icon is"
        }
    }

    fun encodeLiveScreen(frame: ProjectionFrame.LiveScreen): ByteArray {
        val hasLeftAction = frame.leftActionIconId != IconId.IC_NONE
        if (hasLeftAction) {
            require(
                frame.leftActionIconTintColorId != null &&
                frame.leftActionContentIconId != null &&
                frame.leftActionContentIconTintColorId != null &&
                frame.leftActionBgColorId != null
            ) { "Left action requires all 4 attributes (tint, contentIcon, contentTint, bgColor) when leftActionIconId != IC_NONE" }
        } else {
            require(
                frame.leftActionIconTintColorId == null &&
                frame.leftActionContentIconId == null &&
                frame.leftActionContentIconTintColorId == null &&
                frame.leftActionBgColorId == null
            ) { "Left action attributes must be null when leftActionIconId == IC_NONE" }
        }

        val hasRightAction = frame.rightActionIconId != IconId.IC_NONE
        if (hasRightAction) {
            require(
                frame.rightActionIconTintColorId != null &&
                frame.rightActionContentIconId != null &&
                frame.rightActionContentIconTintColorId != null &&
                frame.rightActionBgColorId != null
            ) { "Right action requires all 4 attributes (tint, contentIcon, contentTint, bgColor) when rightActionIconId != IC_NONE" }
        } else {
            require(
                frame.rightActionIconTintColorId == null &&
                frame.rightActionContentIconId == null &&
                frame.rightActionContentIconTintColorId == null &&
                frame.rightActionBgColorId == null
            ) { "Right action attributes must be null when rightActionIconId == IC_NONE" }
        }

        requireConsistentTint("Content icon", frame.contentIconId, frame.contentIconTintColorId)

        val out = ByteArrayOutputStream()

        out.write(CommandId.MYSTERY_BOX.value)
        out.write(frame.requestId and 0xFF)
        out.write(frame.headerBgColorId.id and 0xFF)
        out.putTlvText(frame.headerText, frame.headerTextColorId, ProjectionBuffer.MAX_TEXT_BYTES)
        out.write(BodyType.LIVE_SCREEN.value)
        out.write(frame.bodyBgColorId.id and 0xFF)
        out.write(frame.leftActionIconId.id and 0xFF)
        out.putIdOrOmit(frame.leftActionIconTintColorId?.id)
        out.putIdOrOmit(frame.leftActionContentIconId?.id)
        out.putIdOrOmit(frame.leftActionContentIconTintColorId?.id)
        out.putIdOrOmit(frame.leftActionBgColorId?.id)
        out.write(frame.rightActionIconId.id and 0xFF)
        out.putIdOrOmit(frame.rightActionIconTintColorId?.id)
        out.putIdOrOmit(frame.rightActionContentIconId?.id)
        out.putIdOrOmit(frame.rightActionContentIconTintColorId?.id)
        out.putIdOrOmit(frame.rightActionBgColorId?.id)
        out.write(frame.contentBgColorId.id and 0xFF)
        out.write(frame.contentIconId.id and 0xFF)
        out.putIdOrOmit(frame.contentIconTintColorId?.id)
        out.putTlvText(frame.contentTextLine1, frame.contentTextColorIdLine1, ProjectionBuffer.MAX_TEXT_BYTES)
        out.putTlvText(frame.contentTextLine2, frame.contentTextColorIdLine2, ProjectionBuffer.MAX_TEXT_BYTES)

        return out.toByteArray()
    }

    fun encodeMenuScreen(frame: ProjectionFrame.MenuScreen): ByteArray {
        requireConsistentTint("Up action", frame.upActionIconId, frame.upActionIconTintColorId)
        requireConsistentTint("Down action", frame.downActionIconId, frame.downActionIconTintColorId)
        requireConsistentTint("Left action", frame.leftActionIconId, frame.leftActionIconTintColorId)
        requireConsistentTint("Right action", frame.rightActionIconId, frame.rightActionIconTintColorId)
        requireConsistentTint("Content icon", frame.contentIconId, frame.contentIconTintColorId)

        val out = ByteArrayOutputStream()

        out.write(CommandId.MYSTERY_BOX.value)
        out.write(frame.requestId and 0xFF)
        out.write(frame.headerBgColorId.id and 0xFF)
        out.putTlvText(frame.headerText, frame.headerTextColorId, ProjectionBuffer.MAX_TEXT_BYTES)
        out.write(BodyType.MENU.value)
        out.write(frame.bodyBgColorId.id and 0xFF)
        out.write(frame.upActionIconId.id and 0xFF)
        out.putIdOrOmit(frame.upActionIconTintColorId?.id)
        out.write(frame.downActionIconId.id and 0xFF)
        out.putIdOrOmit(frame.downActionIconTintColorId?.id)
        out.write(frame.leftActionIconId.id and 0xFF)
        out.putIdOrOmit(frame.leftActionIconTintColorId?.id)
        out.write(frame.rightActionIconId.id and 0xFF)
        out.putIdOrOmit(frame.rightActionIconTintColorId?.id)
        out.write(frame.contentIconId.id and 0xFF)
        out.putIdOrOmit(frame.contentIconTintColorId?.id)
        out.putTlvText(frame.contentTextLine1, frame.contentTextColorIdLine1, ProjectionBuffer.MAX_TEXT_BYTES)
        out.putTlvText(frame.contentTextLine2, frame.contentTextColorIdLine2, ProjectionBuffer.MAX_TEXT_BYTES)

        return out.toByteArray()
    }

    fun encodePopUp(frame: ProjectionFrame.PopUp): ByteArray {
        val hasLeft = ProjectionBuffer.isPresent(frame.leftActionIconId)
        val hasRight = ProjectionBuffer.isPresent(frame.rightActionIconId)

        if (hasRight && !hasLeft) {
            throw IllegalArgumentException("right action requires left action")
        }

        val out = ByteArrayOutputStream()

        out.write(CommandId.POP_UP.value)
        out.write(frame.requestId and 0xFF)
        out.write(frame.bgColorId.id and 0xFF)
        out.write(frame.iconId.id and 0xFF)
        out.putIdOrOmit(frame.iconTintColorId?.id)
        out.putTlvText(frame.text, frame.textColorId, ProjectionBuffer.MAX_POPUP_TEXT_BYTES)

        // Optional action block: 0, 6 or 10 bytes, zero-filled rather than omitted.
        if (hasLeft) {
            out.putIdOrZero(frame.leftActionIconId.id)
            out.putIdOrZero(frame.leftActionIconTintColorId?.id)
            out.putIdOrZero(frame.leftActionContentIconId?.id)
            out.putIdOrZero(frame.leftActionContentIconTintColorId?.id)
            out.putIdOrZero(frame.leftActionBgColorId?.id)
            out.putIdOrZero(frame.rightActionIconId.id)

            if (hasRight) {
                out.putIdOrZero(frame.rightActionIconTintColorId?.id)
                out.putIdOrZero(frame.rightActionContentIconId?.id)
                out.putIdOrZero(frame.rightActionContentIconTintColorId?.id)
                out.putIdOrZero(frame.rightActionBgColorId?.id)
            }
        }

        return out.toByteArray()
    }

    fun encodeActiveIndicators(frame: ProjectionFrame.ActiveIndicators): ByteArray {
        val highByte: Byte = if (frame.indicatorMask < 0) 0xFF.toByte() else 0x00.toByte()
        val bitmask: Byte = (frame.indicatorMask and 0xFF).toByte()
        return byteArrayOf(
            CommandId.ACTIVE_INDICATORS_CONTROL.value.toByte(),
            (frame.requestId and 0xFF).toByte(),
            highByte,
            bitmask
        )
    }

    fun encodeRequestSabMode(frame: ProjectionFrame.RequestSabMode): ByteArray {
        return byteArrayOf(
            CommandId.REQUEST_SAB_MODE.value.toByte(),
            (frame.requestId and 0xFF).toByte()
        )
    }

    fun encodeFirmwareUpdate(frame: ProjectionFrame.FirmwareUpdate): ByteArray {
        return byteArrayOf(
            CommandId.FIRMWARE_UPDATE.value.toByte(),
            (frame.requestId and 0xFF).toByte(),
            (frame.status and 0xFF).toByte()
        )
    }
}
