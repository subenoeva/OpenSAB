package com.opensab.cluster

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.IconId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arrow of an action zone is drawn in `PURE_BLACK` over the light shade of the zone, on live
 * screens as on menus. Tinted with the feature colour it sits on a background of its own family
 * and is hard to tell from it. Pinned for every preset that shows an arrow.
 */
class LiveScreenPresetsArrowTintTest {

    private val screensWithArrows = listOf(
        "music" to LiveScreenPresets.music(1, "Music", "Title", "Artist", playing = true),
        "musicError" to LiveScreenPresets.musicError(1, "Music"),
        "navigation" to
            LiveScreenPresets.navigation(1, "Nav", "Turn right", "Main St", IconId.NAVIGATION),
        "navVoice" to LiveScreenPresets.navVoice(
            1, "Nav", "Listening",
            leftActionIcon = IconId.LIVE_CANCEL_TWO_BUTTONS,
            rightActionIcon = IconId.LIVE_SEND
        ),
        "volume" to LiveScreenPresets.volume(1, "Volume", "Mute", percent = 40, isMuted = false),
        "call" to LiveScreenPresets.call(1, "Phone", "Alice", "00:12"),
        "callRinging" to LiveScreenPresets.callRinging(1, "Phone", "Alice", "Ringing"),
        "callDial" to LiveScreenPresets.callDial(
            1, "Phone", "Listening",
            leftActionIcon = IconId.LIVE_CANCEL_TWO_BUTTONS,
            rightActionIcon = IconId.LIVE_SEND
        ),
        "weather" to LiveScreenPresets.weather(1, "Weather", "18C Cloudy", "Vigo"),
        "message" to LiveScreenPresets.message(1, "Messages", "Alice", "On my way", canReply = true),
        "messagePrompt" to LiveScreenPresets.messagePrompt(1, "Messages", "Alice", "Chat"),
        "messageReply" to LiveScreenPresets.messageReply(
            1, "Messages", "Listening", IconId.IC_NONE,
            leftActionIcon = IconId.LIVE_CANCEL_TWO_BUTTONS,
            rightActionIcon = IconId.LIVE_SEND
        )
    )

    @Test
    fun `should tint every action arrow black`() {
        screensWithArrows.forEach { (name, screen) ->
            val tints = listOfNotNull(screen.leftActionIconTintColorId, screen.rightActionIconTintColorId)
            assertTrue("$name shows no arrow, so it pins nothing", tints.isNotEmpty())
            tints.forEach { tint ->
                assertEquals("$name tints an action arrow with something other than black", ColorId.PURE_BLACK, tint)
            }
        }
    }
}
