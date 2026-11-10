package com.opensab.cluster

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionBuffer
import com.opensab.sab.protocol.projection.ProjectionFrame

/**
 * Factory presets for constructing standardized [ProjectionFrame.LiveScreen] instances.
 *
 * **No copy of its own.** Headers, standby lines and the mute word arrive as parameters, because
 * this layer has no idea what language the rider reads: `com.opensab.cluster` holds the resolver,
 * and this file is the one whose bytes are pinned against a BTU 0536.
 *
 * Every screen is shaded from a single feature triple — `color`, `colorDark`, `colorLight`
 * (`WEATHER`/`VOLUME` grey, `NAVIGATION` green, `CALL` purple, `MESSAGE` blue, `MUSIC` orange):
 *
 * - `headerBgColorId` and `bodyBgColorId` both take `color`,
 * - `contentBgColorId` takes `colorDark`,
 * - the action backgrounds take `colorLight`.
 * - the action arrows are tinted `PURE_BLACK`, as on menus.
 *
 * It holds for standby screens too: there is no dark-headed live screen.
 * `LiveScreenPresetsBackgroundTest` pins the rule.
 *
 * A live screen shows **either** a content icon **or** a second line of content text, never both:
 * the BTU rejects the pair with `status=0x0C` pointing at the `contentIconId` byte. Presets whose
 * second line is a parameter therefore drop the icon exactly when that line is filled, which is why
 * the icon survives on every standby screen. `LiveScreenPresetsContentIconTest` pins the rule over
 * the whole catalogue.
 */
object LiveScreenPresets {

    /**
     * The tint reaches the wire exactly when its icon does, so the two are always chosen together
     * (`FrameEncoder.requireConsistentTint`).
     */
    private fun contentIconTintFor(iconId: IconId): ColorId? =
        if (iconId != IconId.IC_NONE) ColorId.PURE_WHITE else null

    fun music(
        requestId: Int,
        header: String,
        title: String,
        artist: String,
        playing: Boolean
    ): ProjectionFrame.LiveScreen {
        val playPauseIcon = if (playing) IconId.LIVE_PAUSE else IconId.LIVE_PLAY
        // The substitute for a missing title *is* the name of the page, which is why it is the same
        // parameter and not a second one: two would be two ways of writing one word and a new way
        // for them to disagree.
        val cleanTitle = title.ifEmpty { header }
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.ORANGE,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.ORANGE,
            leftActionIconId = IconId.LIVE_ARROW_LEFT,
            leftActionIconTintColorId = ColorId.PURE_BLACK,
            leftActionContentIconId = playPauseIcon,
            leftActionContentIconTintColorId = ColorId.PURE_WHITE,
            leftActionBgColorId = ColorId.ORANGE_LIGHT,
            rightActionIconId = IconId.LIVE_ARROW_RIGHT,
            rightActionIconTintColorId = ColorId.PURE_BLACK,
            rightActionContentIconId = IconId.LIVE_NEXT,
            rightActionContentIconTintColorId = ColorId.PURE_WHITE,
            rightActionBgColorId = ColorId.ORANGE_LIGHT,
            contentBgColorId = ColorId.ORANGE_DARK,
            contentIconId = IconId.IC_NONE,
            contentIconTintColorId = null,
            contentTextLine1 = cleanTitle,
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = artist,
            contentTextColorIdLine2 = if (artist.isNotEmpty()) ColorId.PURE_WHITE else null
        )
    }

    fun musicLoading(
        requestId: Int,
        header: String
    ): ProjectionFrame.LiveScreen = ProjectionFrame.LiveScreen(
        requestId = requestId,
        headerBgColorId = ColorId.ORANGE,
        headerText = header,
        headerTextColorId = ColorId.PURE_WHITE,
        bodyBgColorId = ColorId.ORANGE,
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
        contentBgColorId = ColorId.ORANGE_DARK,
        contentIconId = IconId.LOADING,
        contentIconTintColorId = ColorId.PURE_WHITE,
        contentTextLine1 = "",
        contentTextColorIdLine1 = null,
        contentTextLine2 = "",
        contentTextColorIdLine2 = null
    )

    fun musicError(
        requestId: Int,
        header: String
    ): ProjectionFrame.LiveScreen = ProjectionFrame.LiveScreen(
        requestId = requestId,
        headerBgColorId = ColorId.ORANGE,
        headerText = header,
        headerTextColorId = ColorId.PURE_WHITE,
        bodyBgColorId = ColorId.ORANGE,
        leftActionIconId = IconId.LIVE_ARROW_LEFT,
        leftActionIconTintColorId = ColorId.PURE_BLACK,
        leftActionContentIconId = IconId.LIVE_CANCEL_ONE_BUTTON,
        leftActionContentIconTintColorId = ColorId.PURE_WHITE,
        leftActionBgColorId = ColorId.ORANGE_LIGHT,
        rightActionIconId = IconId.IC_NONE,
        rightActionIconTintColorId = null,
        rightActionContentIconId = null,
        rightActionContentIconTintColorId = null,
        rightActionBgColorId = null,
        contentBgColorId = ColorId.ORANGE_DARK,
        contentIconId = IconId.DFU_WARNING,
        contentIconTintColorId = ColorId.PURE_WHITE,
        contentTextLine1 = "",
        contentTextColorIdLine1 = null,
        contentTextLine2 = "",
        contentTextColorIdLine2 = null
    )


    fun navigation(
        requestId: Int,
        header: String,
        instruction: String,
        street: String,
        maneuverIcon: IconId
    ): ProjectionFrame.LiveScreen {
        val contentIcon = if (street.isNotEmpty()) IconId.IC_NONE else maneuverIcon
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.GREEN,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.GREEN,
            leftActionIconId = IconId.LIVE_ARROW_LEFT,
            leftActionIconTintColorId = ColorId.PURE_BLACK,
            leftActionContentIconId = IconId.LIVE_CANCEL_ONE_BUTTON,
            leftActionContentIconTintColorId = ColorId.PURE_WHITE,
            leftActionBgColorId = ColorId.GREEN_LIGHT,
            rightActionIconId = IconId.LIVE_ARROW_RIGHT,
            rightActionIconTintColorId = ColorId.PURE_BLACK,
            rightActionContentIconId = IconId.LIVE_RETRY,
            rightActionContentIconTintColorId = ColorId.PURE_WHITE,
            rightActionBgColorId = ColorId.GREEN_LIGHT,
            contentBgColorId = ColorId.GREEN_DARK,
            contentIconId = contentIcon,
            contentIconTintColorId = contentIconTintFor(contentIcon),
            contentTextLine1 = instruction,
            contentTextColorIdLine1 = if (instruction.isNotEmpty()) ColorId.PURE_WHITE else null,
            contentTextLine2 = street,
            contentTextColorIdLine2 = if (street.isNotEmpty()) ColorId.PURE_WHITE else null
        )
    }

    fun navigationStandby(
        requestId: Int,
        header: String,
        empty: String
    ): ProjectionFrame.LiveScreen {
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.GREEN,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.GREEN,
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
            contentBgColorId = ColorId.GREEN_DARK,
            contentIconId = IconId.NAVIGATION,
            contentIconTintColorId = ColorId.PURE_WHITE,
            contentTextLine1 = empty,
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = "",
            contentTextColorIdLine2 = null
        )
    }

    /**
     * Splits destination query into two lines of at most [ProjectionBuffer.MAX_TEXT_BYTES] UTF-8
     * bytes each, respecting word boundaries whenever possible.
     */
    fun splitDestinationText(query: String): Pair<String, String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Pair("", "")
        if (trimmed.toByteArray(Charsets.UTF_8).size <= ProjectionBuffer.MAX_TEXT_BYTES) {
            return Pair(trimmed, "")
        }

        val words = trimmed.split(Regex("\\s+"))
        val line1Words = mutableListOf<String>()
        var line1ByteCount = 0

        var index = 0
        while (index < words.size) {
            val word = words[index]
            val wordBytes = word.toByteArray(Charsets.UTF_8).size
            val addedBytes = if (line1Words.isEmpty()) wordBytes else wordBytes + 1
            if (line1ByteCount + addedBytes <= ProjectionBuffer.MAX_TEXT_BYTES) {
                line1Words.add(word)
                line1ByteCount += addedBytes
                index++
            } else {
                break
            }
        }

        val line1: String
        val line2Raw: String
        if (line1Words.isEmpty()) {
            val firstWord = words[0]
            line1 = truncateUtf8Safely(firstWord, ProjectionBuffer.MAX_TEXT_BYTES)
            val remainder = firstWord.substring(line1.length) +
                (if (words.size > 1) " " + words.drop(1).joinToString(" ") else "")
            line2Raw = remainder.trim()
        } else {
            line1 = line1Words.joinToString(" ")
            line2Raw = words.drop(index).joinToString(" ")
        }

        val line2 = truncateUtf8Safely(line2Raw, ProjectionBuffer.MAX_TEXT_BYTES)
        return Pair(line1, line2)
    }

    private fun truncateUtf8Safely(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text

        var length = maxBytes
        // Backtrack if length lands inside a multi-byte UTF-8 sequence (continuation byte starts with 10xxxxxx)
        while (length > 0 && (bytes[length].toInt() and 0xC0) == 0x80) {
            length--
        }
        return String(bytes, 0, length, Charsets.UTF_8).trimEnd()
    }

    fun navVoice(
        requestId: Int,
        header: String,
        line1: String,
        line2: String = "",
        contentIconId: IconId = IconId.IC_NONE,
        leftActionIcon: IconId? = null,
        rightActionIcon: IconId? = null,
        rightActionBgColor: ColorId = ColorId.GREEN_LIGHT
    ): ProjectionFrame.LiveScreen {
        val contentIcon = if (line2.isNotEmpty()) IconId.IC_NONE else contentIconId
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.GREEN,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.GREEN,
            leftActionIconId = if (leftActionIcon != null) IconId.LIVE_ARROW_LEFT else IconId.IC_NONE,
            leftActionIconTintColorId = ColorId.PURE_BLACK.takeIf { leftActionIcon != null },
            leftActionContentIconId = leftActionIcon,
            leftActionContentIconTintColorId = ColorId.PURE_WHITE.takeIf { leftActionIcon != null },
            leftActionBgColorId = ColorId.GREEN_LIGHT.takeIf { leftActionIcon != null },
            rightActionIconId = if (rightActionIcon != null) IconId.LIVE_ARROW_RIGHT else IconId.IC_NONE,
            rightActionIconTintColorId = ColorId.PURE_BLACK.takeIf { rightActionIcon != null },
            rightActionContentIconId = rightActionIcon,
            rightActionContentIconTintColorId = ColorId.PURE_WHITE.takeIf { rightActionIcon != null },
            rightActionBgColorId = rightActionBgColor.takeIf { rightActionIcon != null },
            contentBgColorId = ColorId.GREEN_DARK,
            contentIconId = contentIcon,
            contentIconTintColorId = contentIconTintFor(contentIcon),
            contentTextLine1 = line1,
            contentTextColorIdLine1 = ColorId.PURE_WHITE.takeIf { line1.isNotEmpty() },
            contentTextLine2 = line2,
            contentTextColorIdLine2 = ColorId.PURE_WHITE.takeIf { line2.isNotEmpty() }
        )
    }

    fun volume(
        requestId: Int,
        header: String,
        mutedText: String,
        percent: Int,
        isMuted: Boolean
    ): ProjectionFrame.LiveScreen {
        val level = if (isMuted) 0 else {
            if (percent <= 0) 0 else (((percent.coerceIn(1, 100) - 1) * 5) / 100) + 1
        }
        val contentIcon = when (level) {
            0 -> IconId.VOLUME_LEVEL_0
            1 -> IconId.VOLUME_LEVEL_1
            2 -> IconId.VOLUME_LEVEL_2
            3 -> IconId.VOLUME_LEVEL_3
            4 -> IconId.VOLUME_LEVEL_4
            5 -> IconId.VOLUME_LEVEL_5
            else -> IconId.VOLUME_STATIC
        }
        val textLine1 = if (isMuted) mutedText else "$percent%"
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.GREY,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.GREY,
            // The level moves with up and down on this handlebar — the cross sends `PREVIOUS` and
            // `NEXT` from its vertical axis and has no volume on the horizontal one, as measured in
            // the field — so the two blocks promise up and down, not left and right. The sides and
            // the content icons stay as they are: those are the bytes the BTU 0536 already accepts.
            leftActionIconId = IconId.LIVE_ARROW_UP,
            leftActionIconTintColorId = ColorId.PURE_BLACK,
            leftActionContentIconId = IconId.LIVE_VOLUME_DOWN,
            leftActionContentIconTintColorId = ColorId.PURE_WHITE,
            leftActionBgColorId = ColorId.GREY_LIGHT,
            rightActionIconId = IconId.LIVE_ARROW_DOWN,
            rightActionIconTintColorId = ColorId.PURE_BLACK,
            rightActionContentIconId = IconId.LIVE_VOLUME_UP,
            rightActionContentIconTintColorId = ColorId.PURE_WHITE,
            rightActionBgColorId = ColorId.GREY_LIGHT,
            contentBgColorId = ColorId.GREY_DARK,
            contentIconId = contentIcon,
            contentIconTintColorId = ColorId.PURE_WHITE,
            contentTextLine1 = textLine1,
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = "",
            contentTextColorIdLine2 = null
        )
    }

    fun call(
        requestId: Int,
        header: String,
        callerName: String,
        statusText: String
    ): ProjectionFrame.LiveScreen {
        val cleanName = callerName.ifEmpty { header }
        val contentIcon = if (statusText.isNotEmpty()) IconId.IC_NONE else IconId.ACTIVE_CALL
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.PURPLE,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.PURPLE,
            leftActionIconId = IconId.LIVE_ARROW_LEFT,
            leftActionIconTintColorId = ColorId.PURE_BLACK,
            leftActionContentIconId = IconId.LIVE_HANG_UP_ONE_BUTTON,
            leftActionContentIconTintColorId = ColorId.PURE_WHITE,
            leftActionBgColorId = ColorId.NEGATIVE,
            rightActionIconId = IconId.IC_NONE,
            rightActionIconTintColorId = null,
            rightActionContentIconId = null,
            rightActionContentIconTintColorId = null,
            rightActionBgColorId = null,
            contentBgColorId = ColorId.PURPLE_DARK,
            contentIconId = contentIcon,
            contentIconTintColorId = contentIconTintFor(contentIcon),
            contentTextLine1 = cleanName,
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = statusText,
            contentTextColorIdLine2 = if (statusText.isNotEmpty()) ColorId.PURE_WHITE else null
        )
    }

    fun callRinging(
        requestId: Int,
        header: String,
        callerName: String,
        statusText: String
    ): ProjectionFrame.LiveScreen {
        val cleanName = callerName.ifEmpty { header }
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.PURPLE,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.PURPLE,
            leftActionIconId = IconId.LIVE_ARROW_LEFT,
            leftActionIconTintColorId = ColorId.PURE_BLACK,
            leftActionContentIconId = IconId.LIVE_HANG_UP_TWO_BUTTONS,
            leftActionContentIconTintColorId = ColorId.PURE_WHITE,
            leftActionBgColorId = ColorId.NEGATIVE,
            rightActionIconId = IconId.LIVE_ARROW_RIGHT,
            rightActionIconTintColorId = ColorId.PURE_BLACK,
            rightActionContentIconId = IconId.LIVE_CALL,
            rightActionContentIconTintColorId = ColorId.PURE_WHITE,
            rightActionBgColorId = ColorId.POSITIVE,
            contentBgColorId = ColorId.PURPLE_DARK,
            // `INCOMING_CALL` would be the icon here, but only on a face showing the icon alone.
            // The status line is always filled on this face, and icon and second line are
            // exclusive, so the icon can never come back on this screen.
            contentIconId = IconId.IC_NONE,
            contentIconTintColorId = null,
            contentTextLine1 = cleanName,
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = statusText,
            contentTextColorIdLine2 = ColorId.PURE_WHITE
        )
    }

    fun callStandby(
        requestId: Int,
        header: String,
        empty: String
    ): ProjectionFrame.LiveScreen {
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.PURPLE,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.PURPLE,
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
            contentBgColorId = ColorId.PURPLE_DARK,
            contentIconId = IconId.CALL,
            contentIconTintColorId = ColorId.PURE_WHITE,
            contentTextLine1 = empty,
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = "",
            contentTextColorIdLine2 = null
        )
    }

    /**
     * The seven faces of dialling, which differ in five slots and in nothing else.
     *
     * One function and not seven, like [messageReply] and for the same reason: the rule that matters
     * — the frame promises exactly what the key does — would otherwise live in seven places and hold
     * in none, and the icon/second-line rule would have seven chances to be broken instead of one. The faces themselves are `com.opensab.cluster.call.DialState`.
     *
     * Purple, because that is what `RootMenuItem.CALL` declares and the dialler is that entry.
     *
     * [line2] is the number kind, and it is filled on exactly one face: the confirmation, where it
     * is what tells the three rows of a contact with three numbers apart. That is why the icon is
     * dropped when it is present rather than the other way round — on that face the second line
     * **is** the information.
     *
     * @param leftActionIcon what `@Back` does, or null when the face answers no `@Back`.
     * @param rightActionIcon what `@Select` does, or null when the face answers no `@Select`.
     * @param rightActionBgColorId `POSITIVE` on the confirmation, and only there: it is the one
     *   press of this page that puts a call through.
     */
    fun callDial(
        requestId: Int,
        header: String,
        line1: String,
        line2: String = "",
        contentIconId: IconId = IconId.IC_NONE,
        leftActionIcon: IconId? = null,
        rightActionIcon: IconId? = null,
        rightActionBgColorId: ColorId = ColorId.PURPLE_LIGHT
    ): ProjectionFrame.LiveScreen {
        val contentIcon = if (line2.isNotEmpty()) IconId.IC_NONE else contentIconId
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.PURPLE,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.PURPLE,
            leftActionIconId =
                if (leftActionIcon != null) IconId.LIVE_ARROW_LEFT else IconId.IC_NONE,
            leftActionIconTintColorId = ColorId.PURE_BLACK.takeIf { leftActionIcon != null },
            leftActionContentIconId = leftActionIcon,
            leftActionContentIconTintColorId = ColorId.PURE_WHITE.takeIf { leftActionIcon != null },
            leftActionBgColorId = ColorId.PURPLE_LIGHT.takeIf { leftActionIcon != null },
            rightActionIconId =
                if (rightActionIcon != null) IconId.LIVE_ARROW_RIGHT else IconId.IC_NONE,
            rightActionIconTintColorId = ColorId.PURE_BLACK.takeIf { rightActionIcon != null },
            rightActionContentIconId = rightActionIcon,
            rightActionContentIconTintColorId =
                ColorId.PURE_WHITE.takeIf { rightActionIcon != null },
            rightActionBgColorId = rightActionBgColorId.takeIf { rightActionIcon != null },
            contentBgColorId = ColorId.PURPLE_DARK,
            contentIconId = contentIcon,
            contentIconTintColorId = contentIconTintFor(contentIcon),
            contentTextLine1 = line1,
            contentTextColorIdLine1 = ColorId.PURE_WHITE.takeIf { line1.isNotEmpty() },
            contentTextLine2 = line2,
            contentTextColorIdLine2 = ColorId.PURE_WHITE.takeIf { line2.isNotEmpty() }
        )
    }

    /**
     * The icon is `FORECAST` and never a weather condition: `WEATHER_SUNNY`..`WEATHER_FOG` are drawn
     * only by the carousel entry, a `MenuScreen`.
     *
     * It reaches the wire only while there is no location line. With one, the BTU rejects the frame
     * at the `contentIconId` byte (`status=0x0C, errorPosition=21`): that is the icon/second-line
     * rule, not the icon value — swapping the condition for `FORECAST` did not change the verdict.
     */
    fun weather(
        requestId: Int,
        header: String,
        temperatureAndCondition: String,
        location: String
    ): ProjectionFrame.LiveScreen {
        val contentIcon = if (location.isNotEmpty()) IconId.IC_NONE else IconId.FORECAST
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.GREY,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.GREY,
            leftActionIconId = IconId.LIVE_ARROW_LEFT,
            leftActionIconTintColorId = ColorId.PURE_BLACK,
            leftActionContentIconId = IconId.LIVE_CANCEL_TWO_BUTTONS,
            leftActionContentIconTintColorId = ColorId.PURE_WHITE,
            leftActionBgColorId = ColorId.GREY_LIGHT,
            rightActionIconId = IconId.LIVE_ARROW_RIGHT,
            rightActionIconTintColorId = ColorId.PURE_BLACK,
            rightActionContentIconId = IconId.LIVE_RETRY,
            rightActionContentIconTintColorId = ColorId.PURE_WHITE,
            rightActionBgColorId = ColorId.GREY_LIGHT,
            contentBgColorId = ColorId.GREY_DARK,
            contentIconId = contentIcon,
            contentIconTintColorId = contentIconTintFor(contentIcon),
            contentTextLine1 = temperatureAndCondition,
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = location,
            contentTextColorIdLine2 = if (location.isNotEmpty()) ColorId.PURE_WHITE else null
        )
    }

    fun weatherStandby(
        requestId: Int,
        header: String,
        empty: String
    ): ProjectionFrame.LiveScreen {
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.GREY,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.GREY,
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
            contentBgColorId = ColorId.GREY_DARK,
            contentIconId = IconId.NO_WEATHER,
            contentIconTintColorId = ColorId.PURE_WHITE,
            contentTextLine1 = empty,
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = "",
            contentTextColorIdLine2 = null
        )
    }

    /**
     * The reading, with the right block open exactly when there is something behind it.
     *
     * @param canReply whether the live notification published a `RemoteInput`. It governs this
     *   button, the `SELECT` branch of the page and the `PRIMARY` zone of the mirror off one
     *   property, so the three can never disagree — the shape `canEnter` has on the carousel.
     */
    fun message(
        requestId: Int,
        header: String,
        sender: String,
        body: String,
        canReply: Boolean = false
    ): ProjectionFrame.LiveScreen {
        val cleanSender = sender.ifEmpty { header }
        val contentIcon = if (body.isNotEmpty()) IconId.IC_NONE else IconId.MESSAGE
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.BLUE,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.BLUE,
            leftActionIconId = IconId.LIVE_ARROW_LEFT,
            leftActionIconTintColorId = ColorId.PURE_BLACK,
            leftActionContentIconId = IconId.LIVE_CANCEL_TWO_BUTTONS,
            leftActionContentIconTintColorId = ColorId.PURE_WHITE,
            leftActionBgColorId = ColorId.BLUE_LIGHT,
            // Painted exactly when the notification can actually be replied to: not every messaging
            // app publishes a `RemoteInput`, and the same app publishes none on a grouped summary
            // or a muted thread.
            rightActionIconId = if (canReply) IconId.LIVE_ARROW_RIGHT else IconId.IC_NONE,
            rightActionIconTintColorId = ColorId.PURE_BLACK.takeIf { canReply },
            rightActionContentIconId = IconId.LIVE_REPLY.takeIf { canReply },
            rightActionContentIconTintColorId = ColorId.PURE_WHITE.takeIf { canReply },
            rightActionBgColorId = ColorId.POSITIVE.takeIf { canReply },
            contentBgColorId = ColorId.BLUE_DARK,
            contentIconId = contentIcon,
            contentIconTintColorId = contentIconTintFor(contentIcon),
            contentTextLine1 = cleanSender,
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = body,
            contentTextColorIdLine2 = if (body.isNotEmpty()) ColorId.PURE_WHITE else null
        )
    }

    /**
     * The question that comes before the reading: who wrote, on which app, and two ways out.
     *
     * Structurally [message] with the right action block switched on, and that is the whole
     * difference between them. The read page keeps its right button dark because its `SELECT`
     * returns `None`; here `SELECT` opens the reading, so the button is painted. The rule both
     * obey is the same one — the frame promises exactly what the key does — and it happens to point
     * opposite ways on the two pages.
     *
     * The content icon follows [message] byte for byte: an app name on line 2 means `IC_NONE`,
     * because icon and second line are exclusive.
     */
    fun messagePrompt(
        requestId: Int,
        header: String,
        sender: String,
        appName: String
    ): ProjectionFrame.LiveScreen {
        val cleanSender = sender.ifEmpty { header }
        val contentIcon = if (appName.isNotEmpty()) IconId.IC_NONE else IconId.MESSAGE
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.BLUE,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.BLUE,
            leftActionIconId = IconId.LIVE_ARROW_LEFT,
            leftActionIconTintColorId = ColorId.PURE_BLACK,
            leftActionContentIconId = IconId.LIVE_CANCEL_TWO_BUTTONS,
            leftActionContentIconTintColorId = ColorId.PURE_WHITE,
            leftActionBgColorId = ColorId.BLUE_LIGHT,
            rightActionIconId = IconId.LIVE_ARROW_RIGHT,
            rightActionIconTintColorId = ColorId.PURE_BLACK,
            rightActionContentIconId = IconId.LIVE_PLAY,
            rightActionContentIconTintColorId = ColorId.PURE_WHITE,
            rightActionBgColorId = ColorId.POSITIVE,
            contentBgColorId = ColorId.BLUE_DARK,
            contentIconId = contentIcon,
            contentIconTintColorId = contentIconTintFor(contentIcon),
            contentTextLine1 = cleanSender,
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = appName,
            contentTextColorIdLine2 = if (appName.isNotEmpty()) ColorId.PURE_WHITE else null
        )
    }

    /**
     * The seven faces of answering a message, which differ in five slots and in nothing else.
     *
     * One function and not seven because that is what they are: the same blue live screen with the
     * content icon, the line and the two action blocks swapped. Written out seven times, the rule
     * that actually matters — the frame promises exactly what the key does — would live in seven
     * places and hold in none, and the icon/second-line rule would have seven chances to be broken
     * instead of one. The faces themselves are [com.opensab.cluster.message.ReplyState], which is
     * where they belong: the engine decides which one, this decides what one looks like.
     *
     * [line] is always the first line and the second is always empty, so the content icon is always
     * legal. That is not a coincidence of these seven frames but the shape of
     * the page: there is one thing to say at a time — what to do, what was heard, or what happened.
     *
     * @param leftActionIcon what `@Back` does, or null when the face answers no `@Back` at all.
     * @param rightActionIcon what `@Select` does, or null when the face answers no `@Select`.
     * @param rightActionBgColorId the shade behind the right block. `POSITIVE` is the send, and it
     *   is the only green button of the page: nothing else here is the last press before a message
     *   leaves the phone.
     */
    fun messageReply(
        requestId: Int,
        header: String,
        line: String,
        contentIconId: IconId,
        leftActionIcon: IconId? = null,
        rightActionIcon: IconId? = null,
        rightActionBgColorId: ColorId = ColorId.BLUE_LIGHT
    ): ProjectionFrame.LiveScreen {
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.BLUE,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.BLUE,
            leftActionIconId = if (leftActionIcon != null) IconId.LIVE_ARROW_LEFT else IconId.IC_NONE,
            leftActionIconTintColorId = ColorId.PURE_BLACK.takeIf { leftActionIcon != null },
            leftActionContentIconId = leftActionIcon,
            leftActionContentIconTintColorId = ColorId.PURE_WHITE.takeIf { leftActionIcon != null },
            leftActionBgColorId = ColorId.BLUE_LIGHT.takeIf { leftActionIcon != null },
            rightActionIconId =
                if (rightActionIcon != null) IconId.LIVE_ARROW_RIGHT else IconId.IC_NONE,
            rightActionIconTintColorId = ColorId.PURE_BLACK.takeIf { rightActionIcon != null },
            rightActionContentIconId = rightActionIcon,
            rightActionContentIconTintColorId = ColorId.PURE_WHITE.takeIf { rightActionIcon != null },
            rightActionBgColorId = rightActionBgColorId.takeIf { rightActionIcon != null },
            contentBgColorId = ColorId.BLUE_DARK,
            contentIconId = contentIconId,
            contentIconTintColorId = contentIconTintFor(contentIconId),
            contentTextLine1 = line,
            contentTextColorIdLine1 = ColorId.PURE_WHITE.takeIf { line.isNotEmpty() },
            contentTextLine2 = "",
            contentTextColorIdLine2 = null
        )
    }

    fun messageStandby(
        requestId: Int,
        header: String,
        empty: String
    ): ProjectionFrame.LiveScreen {
        return ProjectionFrame.LiveScreen(
            requestId = requestId,
            headerBgColorId = ColorId.BLUE,
            headerText = header,
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.BLUE,
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
            contentBgColorId = ColorId.BLUE_DARK,
            contentIconId = IconId.MESSAGE,
            contentIconTintColorId = ColorId.PURE_WHITE,
            contentTextLine1 = empty,
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = "",
            contentTextColorIdLine2 = null
        )
    }

    fun idle(requestId: Int): ProjectionFrame.LiveScreen {
        return ProjectionFrame.LiveScreen(
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
}
