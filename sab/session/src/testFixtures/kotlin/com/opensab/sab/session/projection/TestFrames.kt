package com.opensab.sab.session.projection

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame

/**
 * The two live frames the projector tests need, built by hand from the wire types.
 *
 * **Owner: :sab:session's own tests.** `engine.LiveScreenPresets` is the product's screen
 * vocabulary and belongs to :cluster; the session module cannot see it and should not want to.
 * `TestFramesTest` pins these byte for byte against what the preset produced.
 */
object TestFrames {

    fun music(
        requestId: Int,
        header: String,
        title: String,
        artist: String,
        playing: Boolean
    ): ProjectionFrame.LiveScreen = ProjectionFrame.LiveScreen(
        requestId = requestId,
        headerBgColorId = ColorId.ORANGE,
        headerText = header,
        headerTextColorId = ColorId.PURE_WHITE,
        bodyBgColorId = ColorId.ORANGE,
        leftActionIconId = IconId.LIVE_ARROW_LEFT,
        leftActionIconTintColorId = ColorId.ORANGE,
        leftActionContentIconId = if (playing) IconId.LIVE_PAUSE else IconId.LIVE_PLAY,
        leftActionContentIconTintColorId = ColorId.PURE_WHITE,
        leftActionBgColorId = ColorId.ORANGE_LIGHT,
        rightActionIconId = IconId.LIVE_ARROW_RIGHT,
        rightActionIconTintColorId = ColorId.ORANGE,
        rightActionContentIconId = IconId.LIVE_NEXT,
        rightActionContentIconTintColorId = ColorId.PURE_WHITE,
        rightActionBgColorId = ColorId.ORANGE_LIGHT,
        contentBgColorId = ColorId.ORANGE_DARK,
        contentIconId = IconId.IC_NONE,
        contentIconTintColorId = null,
        contentTextLine1 = title.ifEmpty { header },
        contentTextColorIdLine1 = ColorId.PURE_WHITE,
        contentTextLine2 = artist,
        contentTextColorIdLine2 = if (artist.isNotEmpty()) ColorId.PURE_WHITE else null
    )

    fun idle(requestId: Int): ProjectionFrame.LiveScreen = ProjectionFrame.LiveScreen(
        requestId = requestId,
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
}
