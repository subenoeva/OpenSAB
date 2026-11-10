package com.opensab.cluster.mirror

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.session.projection.PopUpPriority

/**
 * What an action means to the rider, independent of which physical button carries it.
 * The UI layer maps each token to a localized label and a Material icon; the engine never
 * knows either.
 */
enum class MirrorActionToken {
    // Carousel and stack
    CAROUSEL_PREVIOUS, CAROUSEL_NEXT, OPEN, GO_BACK, OPEN_ROOT_MENU,

    // Media
    PLAY, PAUSE, NEXT_TRACK,

    // Volume, shared by every page that owns a volume source
    VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE,

    // Telephony
    ACCEPT_CALL, REJECT_CALL, HANG_UP, PLACE_CALL, RETRY_CALL,

    // Page specific
    REPEAT_MANEUVER, REPEAT_FORECAST, REFRESH_WEATHER, DISMISS_MESSAGE, READ_MESSAGE,

    // Replying to a message
    REPLY_MESSAGE, SEND_MESSAGE, RETRY_DICTATION, CANCEL_DICTATION, QUICK_REPLIES,

    // Modal layer
    DISMISS
}

/**
 * Where the action sits on the mirrored card. A layout hint for the UI, and unique within a page:
 * two actions sharing a slot have no place to be drawn.
 *
 * [HEADER] is reserved for the escape back to the root carousel, which every page maps to `MENU`.
 */
enum class MirrorSlot { LEFT, RIGHT, PRIMARY, UP, DOWN, HEADER }

/**
 * One touch target of the mirror. A tap emits exactly [functionId] as a synthetic handlebar press.
 *
 * [aliases] holds the other FunctionIds that the page routes to the same effect — several pages
 * map `PREVIOUS` and `VOLUME_UP` to the same volume source. They are declared so the contract
 * harness can prove no button is silently unreachable, without the mirror painting a duplicate
 * zone for each one.
 */
data class MirrorAction(
    val token: MirrorActionToken,
    val functionId: FunctionId,
    val slot: MirrorSlot,
    val aliases: Set<FunctionId> = emptySet(),
    val enabled: Boolean = true
)

/**
 * Semantic twin of the frame currently mounted on the cluster.
 *
 * [title] and [lines] are the very strings the cluster received, shown verbatim: they are the
 * rider's context, not app copy. Everything the app words itself travels as a token.
 */
data class MirrorPage(
    val pageName: String,
    val title: String?,
    val lines: List<String>,
    val accent: ColorId,
    val actions: List<MirrorAction>
)

/**
 * Modal layer mirrored above the page. While this is non-null every page action is inert:
 * `PopupManager.onInput` swallows the press before the page ever sees it.
 */
data class MirrorPopUp(
    val id: String,
    val message: String,
    val priority: PopUpPriority,
    val actions: List<MirrorAction>
)

/**
 * The requestId a page renders with when it is only being read for the mirror.
 *
 * The mirror never reaches the wire, so no Receipt will ever be matched against this frame and the
 * counter must not be spent on it: `RequestIdGenerator` is the single source of projection ids and
 * every id it hands out belongs to a frame that was actually written.
 */
const val MIRROR_REQUEST_ID: Int = 0

/**
 * Projects a rendered frame onto the text slots of a [MirrorPage]: `title` is the header, `lines`
 * are the non-empty content lines and `accent` is the header background.
 *
 * Reading them off the frame instead of rebuilding them is what makes drift impossible rather than
 * merely detectable: a page cannot change what it projects and forget to change what it mirrors.
 *
 * `LiveScreen` and `MenuScreen` carry these three slots but share no supertype that declares them,
 * hence the branch. Any other variant is a page rendering something the mirror has no twin for, and
 * fails loudly instead of mirroring a blank card.
 */
fun mirrorPageOf(
    pageName: String,
    frame: ProjectionFrame,
    actions: List<MirrorAction>
): MirrorPage = when (frame) {
    is ProjectionFrame.LiveScreen -> MirrorPage(
        pageName = pageName,
        title = frame.headerText,
        lines = contentLines(frame.contentTextLine1, frame.contentTextLine2),
        accent = frame.headerBgColorId,
        actions = actions
    )

    is ProjectionFrame.MenuScreen -> MirrorPage(
        pageName = pageName,
        title = frame.headerText,
        lines = contentLines(frame.contentTextLine1, frame.contentTextLine2),
        accent = frame.headerBgColorId,
        actions = actions
    )

    else -> error(
        "Page $pageName renders a ${frame::class.simpleName}, which carries no header or content " +
            "slots for the mirror to twin"
    )
}

private fun contentLines(line1: String?, line2: String?): List<String> =
    listOfNotNull(line1, line2).filter { it.isNotEmpty() }
