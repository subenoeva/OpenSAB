package com.opensab.cluster

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.IconId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `LiveScreen` shows **either** a content icon **or** a second line of content text, never both.
 *
 * Measured on the wire in the field. Of the 109 `LiveScreen` frames of one pass that got a
 * receipt, the split is exceptionless:
 *
 * | `contentIconId` | `contentTextLine2` | verdict | n |
 * |---|---|---|---|
 * | `IC_NONE` | present | acknowledged | 26 (`MusicPage`) |
 * | set | empty | acknowledged | 60 (`VolumePage`, call standby) |
 * | set | present | **rejected** | 20 (`CallPage` 13, `WeatherPage` 4, `MessagePage` 3) |
 *
 * Every rejection is `status=0x0C` with `errorPosition` landing exactly on the `contentIconId`
 * byte — 21, 22 or 23, moving with the length of the header text. The icon *value* is not the
 * discriminator: `CallPage` is accepted with `CALL` (1) and rejected with `INCOMING_CALL` (107),
 * `VolumePage` is accepted with six different ids (118..123). Neither is frame length: 26 accepted frames are
 * longer than the shortest rejected one.
 *
 * This walks the whole preset catalogue, in both the variant that carries a second line and the one
 * that does not, so no future live screen can bring the pair back.
 */
class LiveScreenPresetsContentIconTest {

    /**
     * The condition icons of the assignment table, drawn only by the carousel entry; the live
     * weather page sends `FORECAST` instead. A convention, not a rule the BTU has been seen to
     * enforce on its own: the rejection that first raised it carried a location line too, so the
     * rule above already explains it.
     */
    private val weatherConditionIcons = setOf(
        IconId.WEATHER_SUNNY,
        IconId.WEATHER_CLEAR,
        IconId.WEATHER_SUN_AND_CLOUD,
        IconId.WEATHER_CLOUDY,
        IconId.WEATHER_RAIN,
        IconId.WEATHER_STORM,
        IconId.WEATHER_SNOW,
        IconId.WEATHER_FOG
    )

    private val liveScreens = listOf(
        "music" to LiveScreenPresets.music(1, "Music", "Title", "Artist", playing = true),
        "music without artist" to LiveScreenPresets.music(1, "Music", "Title", "", playing = true),
        "musicLoading" to LiveScreenPresets.musicLoading(1, "Loading"),
        "musicError" to LiveScreenPresets.musicError(1, "App Error"),
        "navigation" to
            LiveScreenPresets.navigation(1, "Nav", "Turn right", "Main St", IconId.DA_TURN_RIGHT),
        "navigation without street" to
            LiveScreenPresets.navigation(1, "Nav", "Turn right", "", IconId.DA_TURN_RIGHT),
        "navigationStandby" to
            LiveScreenPresets.navigationStandby(1, "Navigation", "No Navigation"),
        "volume" to LiveScreenPresets.volume(1, "Volume", "Mute", percent = 40, isMuted = false),
        "call" to LiveScreenPresets.call(1, "Phone", "Alice", "00:12"),
        "call without status" to LiveScreenPresets.call(1, "Phone", "Alice", ""),
        "callRinging" to LiveScreenPresets.callRinging(1, "Phone", "Alice", "Ringing"),
        "callStandby" to LiveScreenPresets.callStandby(1, "Phone", "No Call"),
        "weather" to LiveScreenPresets.weather(1, "Weather", "18C Cloudy", "Vigo"),
        "weather without location" to LiveScreenPresets.weather(1, "Weather", "18C Cloudy", ""),
        "weatherStandby" to LiveScreenPresets.weatherStandby(1, "Weather", "No Data"),
        "message" to LiveScreenPresets.message(1, "Messages", "Alice", "On my way"),
        "message that can be replied to" to
            LiveScreenPresets.message(1, "Messages", "Alice", "On my way", canReply = true),
        "message without body that can be replied to" to
            LiveScreenPresets.message(1, "Messages", "Alice", "", canReply = true),
        "message without body" to LiveScreenPresets.message(1, "Messages", "Alice", ""),
        "messagePrompt" to LiveScreenPresets.messagePrompt(1, "Messages", "Alice", "WhatsApp"),
        "messagePrompt without app name" to
            LiveScreenPresets.messagePrompt(1, "Messages", "Alice", ""),
        "messageStandby" to LiveScreenPresets.messageStandby(1, "Messages", "No Messages"),
        // The seven faces of the reply page. They all carry a content icon and never a second
        // line, which is the shape of the page and not a coincidence of these seven frames: there
        // is one thing to say at a time.
        "messageReply listening" to LiveScreenPresets.messageReply(
            1, "Messages", "Say your message", IconId.MIC,
            leftActionIcon = IconId.LIVE_CANCEL_ONE_BUTTON
        ),
        "messageReply confirming" to LiveScreenPresets.messageReply(
            1,
            "Messages",
            "On my way",
            IconId.REVIEW,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = IconId.LIVE_SEND,
            rightActionBgColorId = ColorId.POSITIVE
        ),
        "messageReply nothing understood" to LiveScreenPresets.messageReply(
            1, "Messages", "I didn't catch that", IconId.MIC, leftActionIcon = IconId.LIVE_RETRY
        ),
        "messageReply failed" to LiveScreenPresets.messageReply(
            1, "Messages", "Microphone denied", IconId.MIC, leftActionIcon = IconId.LIVE_RETRY
        ),
        "messageReply sending" to
            LiveScreenPresets.messageReply(1, "Messages", "Sending...", IconId.LOADING),
        "messageReply sent" to
            LiveScreenPresets.messageReply(1, "Messages", "Sent", IconId.SEND),
        "messageReply not sent" to LiveScreenPresets.messageReply(
            1, "Messages", "Not sent", IconId.NO_NETWORK, leftActionIcon = IconId.LIVE_RETRY
        ),
        "callDial listening" to LiveScreenPresets.callDial(
            1, "Phone", "Say a name", contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_CANCEL_ONE_BUTTON
        ),
        "callDial confirming" to LiveScreenPresets.callDial(
            1, "Phone", "Ana Ruiz", "Mobile", IconId.IC_NONE,
            leftActionIcon = IconId.LIVE_RETRY,
            rightActionIcon = IconId.LIVE_CALL,
            rightActionBgColorId = ColorId.POSITIVE
        ),
        "callDial nothing understood" to LiveScreenPresets.callDial(
            1, "Phone", "I didn't catch that", contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY
        ),
        "callDial no match" to LiveScreenPresets.callDial(
            1, "Phone", "Not in contacts", contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY
        ),
        "callDial failed" to LiveScreenPresets.callDial(
            1, "Phone", "Microphone denied", contentIconId = IconId.MIC,
            leftActionIcon = IconId.LIVE_RETRY
        ),
        "callDial placing" to
            LiveScreenPresets.callDial(1, "Phone", "Calling...", contentIconId = IconId.LOADING),
        "callDial not placed" to LiveScreenPresets.callDial(
            1, "Phone", "Call failed", contentIconId = IconId.CANCEL,
            leftActionIcon = IconId.LIVE_RETRY
        ),
        "navVoice listening" to LiveScreenPresets.navVoice(
            1, "Nav", "Listening", "", IconId.MIC, IconId.LIVE_CANCEL_ONE_BUTTON
        ),
        "navVoice confirming" to LiveScreenPresets.navVoice(
            1, "Nav", "Repsol", "Castellana", IconId.MIC, IconId.LIVE_RETRY, IconId.LIVE_ACCEPT
        ),
        "navVoice starting" to LiveScreenPresets.navVoice(1, "Nav", "Starting", "", IconId.LOADING),
        "idle" to LiveScreenPresets.idle(1)
    )

    /** The names of the presets that must keep drawing an icon, because they have no second line. */
    private val iconBearingScreens = setOf(
        "musicLoading",
        "musicError",
        "navigationStandby",
        "volume",
        "call without status",
        "callStandby",
        "weather without location",
        "weatherStandby",
        "message without body",
        "message without body that can be replied to",
        "messagePrompt without app name",
        "messageStandby",
        "messageReply listening",
        "messageReply confirming",
        "messageReply nothing understood",
        "messageReply failed",
        "messageReply sending",
        "messageReply sent",
        "messageReply not sent",
        "callDial listening",
        "callDial nothing understood",
        "callDial no match",
        "callDial failed",
        "callDial placing",
        "callDial not placed",
        "navVoice listening",
        "navVoice starting"
    )

    @Test
    fun `should never send a content icon together with a second content line`() {
        liveScreens.forEach { (name, screen) ->
            val hasContentIcon = screen.contentIconId != IconId.IC_NONE
            val hasSecondLine = !screen.contentTextLine2.isNullOrEmpty()

            assertFalse(
                "$name sends ${screen.contentIconId} (id ${screen.contentIconId.id}) as its " +
                    "contentIconId while its second content line reads " +
                    "\"${screen.contentTextLine2}\"; the BTU rejects that pair with status=0x0C " +
                    "at the contentIconId byte",
                hasContentIcon && hasSecondLine
            )
        }
    }

    @Test
    fun `should drop the content icon tint along with the icon`() {
        liveScreens.forEach { (name, screen) ->
            assertEquals(
                "$name leaves its contentIconTintColorId out of step with its contentIconId, " +
                    "which shifts every field after it on the wire",
                screen.contentIconId != IconId.IC_NONE,
                screen.contentIconTintColorId != null
            )
        }
    }

    @Test
    fun `should still send a content icon when there is no second content line`() {
        liveScreens
            .filter { (name, _) -> name in iconBearingScreens }
            .forEach { (name, screen) ->
                assertTrue(
                    "$name has no second content line, so it may and must keep its content icon; " +
                        "it sends IC_NONE instead",
                    screen.contentIconId != IconId.IC_NONE
                )
            }
    }

    @Test
    fun `should never send a weather condition icon as the content icon of a live screen`() {
        liveScreens.forEach { (name, screen) ->
            assertTrue(
                "$name sends ${screen.contentIconId} (id ${screen.contentIconId.id}) as its " +
                    "contentIconId, and the weather conditions are a MenuScreen icon space",
                screen.contentIconId !in weatherConditionIcons
            )
        }
    }

    @Test
    fun `should send the forecast icon on a live weather screen without a location`() {
        val screen = LiveScreenPresets.weather(1, "Weather", "18C Cloudy", "")

        assertEquals(
            "the live weather screen with no location line drops its content icon instead of " +
                "sending FORECAST",
            IconId.FORECAST,
            screen.contentIconId
        )
    }
}
