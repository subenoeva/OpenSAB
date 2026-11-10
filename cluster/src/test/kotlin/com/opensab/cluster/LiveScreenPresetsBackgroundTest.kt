package com.opensab.cluster

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.IconId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every live screen is painted from a single feature triple (`color`, `colorDark`, `colorLight`):
 * `color` is **both** the header and the body background and `colorDark` is reserved for the
 * content area. Pinned for the whole preset catalogue so no screen ships a body a shade darker.
 */
class LiveScreenPresetsBackgroundTest {

    /** The `color` → `colorDark` pairs of the feature triples. */
    private val darkShadeOf = mapOf(
        ColorId.GREEN to ColorId.GREEN_DARK,
        ColorId.PURPLE to ColorId.PURPLE_DARK,
        ColorId.BLUE to ColorId.BLUE_DARK,
        ColorId.ORANGE to ColorId.ORANGE_DARK,
        ColorId.GREY to ColorId.GREY_DARK
    )

    private val featureScreens = listOf(
        "music" to LiveScreenPresets.music(1, "Music", "Title", "Artist", playing = true),
        "navigation" to
            LiveScreenPresets.navigation(1, "Nav", "Turn right", "Main St", IconId.NAVIGATION),
        "navigationStandby" to
            LiveScreenPresets.navigationStandby(1, "Navigation", "No Navigation"),
        "volume" to LiveScreenPresets.volume(1, "Volume", "Mute", percent = 40, isMuted = false),
        "call" to LiveScreenPresets.call(1, "Phone", "Alice", "00:12"),
        "callRinging" to LiveScreenPresets.callRinging(1, "Phone", "Alice", "Ringing"),
        "callStandby" to LiveScreenPresets.callStandby(1, "Phone", "No Call"),
        "weather" to LiveScreenPresets.weather(1, "Weather", "18C Cloudy", "Vigo"),
        "weatherStandby" to LiveScreenPresets.weatherStandby(1, "Weather", "No Data"),
        "message" to LiveScreenPresets.message(1, "Messages", "Alice", "On my way"),
        "messageStandby" to LiveScreenPresets.messageStandby(1, "Messages", "No Messages")
    )

    @Test
    fun `should paint the body with the header colour on every feature screen`() {
        featureScreens.forEach { (name, screen) ->
            assertEquals(
                "$name paints its body with a different colour than its header",
                screen.headerBgColorId,
                screen.bodyBgColorId
            )
        }
    }

    @Test
    fun `should paint the content with the dark shade of the header colour on every feature screen`() {
        featureScreens.forEach { (name, screen) ->
            val dark = requireNotNull(darkShadeOf[screen.headerBgColorId]) {
                "$name uses ${screen.headerBgColorId} as header colour, which is not a feature colour"
            }
            assertEquals(
                "$name does not paint its content area with the dark shade of its feature colour",
                dark,
                screen.contentBgColorId
            )
        }
    }

    @Test
    fun `should keep the idle screen fully black`() {
        val idle = LiveScreenPresets.idle(1)

        assertEquals(ColorId.PURE_BLACK, idle.headerBgColorId)
        assertEquals(ColorId.PURE_BLACK, idle.bodyBgColorId)
        assertEquals(ColorId.PURE_BLACK, idle.contentBgColorId)
    }
}
