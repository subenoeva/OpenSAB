package com.opensab.sab.session.projection

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.session.vehicle.VehicleCapabilities

/**
 * Adapts a frame to what the connected cluster can render, immediately before it is encoded.
 *
 * This is the single degradation point for frame content in the whole engine: page providers and
 * adapters build the richest frame they can and stay ignorant of the hardware, which is what lets
 * [com.opensab.cluster.BaseStateManager] stay one singleton that outlives any given motorcycle.
 */
class VehicleFramePolicy(
    private val sanitizer: TextSanitizer
) {

    fun adapt(frame: ProjectionFrame, caps: VehicleCapabilities): ProjectionFrame = when (frame) {
        is ProjectionFrame.LiveScreen -> {
            val header = frame.headerText.sanitized(caps)
            val line1 = frame.contentTextLine1.sanitized(caps)
            val line2 = frame.contentTextLine2.sanitized(caps)
            frame.copy(
                headerBgColorId = caps.background(frame.headerBgColorId),
                headerText = header,
                headerTextColorId = caps.pair(frame.headerTextColorId, header),
                bodyBgColorId = caps.background(frame.bodyBgColorId),
                leftActionIconTintColorId = caps.foreground(frame.leftActionIconTintColorId),
                leftActionContentIconTintColorId =
                    caps.foreground(frame.leftActionContentIconTintColorId),
                leftActionBgColorId = caps.background(frame.leftActionBgColorId),
                rightActionIconTintColorId = caps.foreground(frame.rightActionIconTintColorId),
                rightActionContentIconTintColorId =
                    caps.foreground(frame.rightActionContentIconTintColorId),
                rightActionBgColorId = caps.background(frame.rightActionBgColorId),
                contentBgColorId = caps.background(frame.contentBgColorId),
                contentIconTintColorId = caps.foreground(frame.contentIconTintColorId),
                contentTextLine1 = line1,
                contentTextColorIdLine1 = caps.pair(frame.contentTextColorIdLine1, line1),
                contentTextLine2 = line2,
                contentTextColorIdLine2 = caps.pair(frame.contentTextColorIdLine2, line2)
            )
        }

        is ProjectionFrame.MenuScreen -> {
            val header = frame.headerText.sanitized(caps)
            val line1 = frame.contentTextLine1.sanitized(caps)
            val line2 = frame.contentTextLine2.sanitized(caps)
            frame.copy(
                headerBgColorId = caps.background(frame.headerBgColorId),
                headerText = header,
                headerTextColorId = caps.pair(frame.headerTextColorId, header),
                bodyBgColorId = caps.background(frame.bodyBgColorId),
                upActionIconTintColorId = caps.foreground(frame.upActionIconTintColorId),
                downActionIconTintColorId = caps.foreground(frame.downActionIconTintColorId),
                leftActionIconTintColorId = caps.foreground(frame.leftActionIconTintColorId),
                rightActionIconTintColorId = caps.foreground(frame.rightActionIconTintColorId),
                contentIconTintColorId = caps.foreground(frame.contentIconTintColorId),
                contentTextLine1 = line1,
                contentTextColorIdLine1 = caps.pair(frame.contentTextColorIdLine1, line1),
                contentTextLine2 = line2,
                contentTextColorIdLine2 = caps.pair(frame.contentTextColorIdLine2, line2)
            )
        }

        is ProjectionFrame.PopUp -> {
            val text = frame.text.sanitized(caps)
            frame.copy(
                bgColorId = caps.background(frame.bgColorId),
                iconTintColorId = caps.foreground(frame.iconTintColorId),
                text = text,
                textColorId = caps.pair(frame.textColorId, text),
                leftActionIconTintColorId = caps.foreground(frame.leftActionIconTintColorId),
                leftActionContentIconTintColorId =
                    caps.foreground(frame.leftActionContentIconTintColorId),
                leftActionBgColorId = caps.background(frame.leftActionBgColorId),
                rightActionIconTintColorId = caps.foreground(frame.rightActionIconTintColorId),
                rightActionContentIconTintColorId =
                    caps.foreground(frame.rightActionContentIconTintColorId),
                rightActionBgColorId = caps.background(frame.rightActionBgColorId)
            )
        }

        // Indicator masks and control packets carry neither colour nor text.
        is ProjectionFrame.ActiveIndicators,
        is ProjectionFrame.DismissModal,
        is ProjectionFrame.RequestSabMode,
        is ProjectionFrame.FirmwareUpdate -> frame
    }

    /** Folds the plain message of a queued popup, which never reaches [adapt] as a frame. */
    fun sanitizeMessage(message: String, caps: VehicleCapabilities): String =
        sanitizer.sanitize(message, caps.segmentDisplay)

    private fun String?.sanitized(caps: VehicleCapabilities): String? =
        this?.let { sanitizer.sanitize(it, caps.segmentDisplay) }

    /**
     * A segment cluster has no palette, so every id collapses onto the two the protocol
     * guarantees.
     *
     * Which of the two is decided by the field's ROLE, never by the colour's brightness. Folding by
     * luminance looks reasonable until GREY_LIGHT — the artist line of the music preset — lands on
     * black over a black background and the line disappears. Backgrounds go dark, everything drawn
     * on top of them goes light, which is what a monochrome panel physically is.
     *
     * A null stays null: putIdOrOmit makes an absent optional vanish from the buffer, so inventing
     * a colour for one would shift every field after it and the BTU would reject the frame.
     */
    private fun VehicleCapabilities.background(color: ColorId): ColorId =
        if (supportsColor) color else ColorId.PURE_BLACK

    @JvmName("backgroundOrNull")
    private fun VehicleCapabilities.background(color: ColorId?): ColorId? =
        color?.let { background(it) }

    private fun VehicleCapabilities.foreground(color: ColorId?): ColorId? =
        if (color == null || supportsColor) color else ColorId.PURE_WHITE

    /**
     * Keeps the TLV invariant [com.opensab.sab.protocol.projection.ProjectionBuffer.putTlvText]
     * enforces: a colour id must be present exactly when the text is.
     *
     * Sanitising a title that was entirely Thai empties it, and handing the encoder an empty string
     * with a non-null colour throws in the middle of the render loop.
     */
    private fun VehicleCapabilities.pair(color: ColorId?, text: String?): ColorId? =
        if (text.isNullOrEmpty()) null else foreground(color)
}
