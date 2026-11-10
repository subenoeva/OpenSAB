package com.opensab.cluster.rootmenu

import com.opensab.cluster.allRootMenuEntries
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallStatus
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.TrackMetadata
import com.opensab.domain.nav.ManeuverType
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.weather.WeatherInfo
import com.opensab.domain.weather.WmoCaption
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The icon/second-line rule on the `MENU` body: a carousel entry sends **either** a content icon
 * **or** a second line of content text, never both.
 *
 * It was first measured on `LiveScreen` and read as exclusive to the live body. A field capture of
 * this very page says otherwise:
 *
 * ```
 * 12:38:36.280 Emitting projection frame for page RootMenu (requestId=101)
 * TX 01 65 0B 08 "Messages" 00 01 0A 03 01 04 01 00 02 01 04 00 04 "Mama" 00 08 "Probando" 10
 *                                                        ^22 contentIconId = 04 (MESSAGE)
 * 12:38:36.317 BTU rejected projection frame: commandId=0x01, requestId=101, status=0x0C,
 *              errorPosition=22
 * ```
 *
 * Eight of those, every one of them the message entry with a message behind it, every one rejected
 * at the `contentIconId` byte. The entry seen accepted was the **call** one — and that one already
 * sends `IC_NONE` while it carries a status line. The carousel entry with no message
 * (`04 00 00 00`: icon, no lines) is acknowledged, so it is the pair and not the icon.
 *
 * What the rider sees is the entry going missing: the cluster keeps painting the previous item, the
 * mirror — published before the verdict, deliberately — shows the message that never arrived.
 */
class RootMenuContentIconTest {

    private fun frames(): List<Pair<String, ProjectionFrame.MenuScreen>> {
        val menu = RootMenuPageProvider(
            entries = allRootMenuEntries(),
            musicState = MutableStateFlow(
                MusicState(
                    metadata = TrackMetadata(title = "Grief", artist = "Earl Sweatshirt"),
                    status = PlaybackStatus.PLAYING
                )
            ),
            navInstruction = MutableStateFlow(
                NavInstruction(
                    title = "Rúa do Sol",
                    streetName = "Rúa do Sol",
                    distanceFormatted = "300 m",
                    maneuver = ManeuverType.TURN_RIGHT
                )
            ),
            activeCall = MutableStateFlow(
                CallInfo("Abuelo Ejemplo", "600400400", CallStatus.RINGING)
            ),
            currentWeather = MutableStateFlow(
                WeatherInfo(
                    locationName = "Porto do Exemplo",
                    temperature = 25,
                    temperatureUnit = "C",
                    caption = WmoCaption.CLEAR
                )
            ),
            currentMessage = MutableStateFlow(MessageInfo("Mama", "Probando", "WhatsApp")),
            pageFactory = { _ -> error("no entry is opened here") },
            copy = englishClusterText
        )

        return RootMenuItem.entries.mapIndexed { index, item ->
            repeat(index) { menu.onInput(com.opensab.sab.protocol.assignment.FunctionId.NEXT) }
            val frame = menu.render(1) as ProjectionFrame.MenuScreen
            repeat(index) { menu.onInput(com.opensab.sab.protocol.assignment.FunctionId.PREVIOUS) }
            item.name to frame
        }
    }

    @Test
    fun `should never send a content icon together with a second content line`() {
        frames().forEach { (name, frame) ->
            assertFalse(
                "the $name entry sends ${frame.contentIconId} (id ${frame.contentIconId.id}) as " +
                    "its contentIconId while its second content line reads " +
                    "\"${frame.contentTextLine2}\"; the BTU rejects that pair with status=0x0C at " +
                    "the contentIconId byte",
                frame.contentIconId != IconId.IC_NONE && !frame.contentTextLine2.isNullOrEmpty()
            )
        }
    }

    @Test
    fun `should drop the content icon tint along with the icon`() {
        frames().forEach { (name, frame) ->
            assertEquals(
                "the $name entry leaves its contentIconTintColorId out of step with its " +
                    "contentIconId, which shifts every field after it on the wire",
                frame.contentIconId != IconId.IC_NONE,
                frame.contentIconTintColorId != null
            )
        }
    }

    /**
     * The other half of the rule: an entry with nothing on its second line keeps its icon. Without
     * this the fix could be "never send a content icon", and the carousel would lose the six
     * feature icons.
     */
    @Test
    fun `should keep the feature icon on an entry with no second line`() {
        val idle = RootMenuPageProvider(
            entries = allRootMenuEntries(),
            pageFactory = { _ -> error("no entry is opened here") },
            copy = englishClusterText
        )

        RootMenuItem.entries.forEachIndexed { index, item ->
            repeat(index) { idle.onInput(com.opensab.sab.protocol.assignment.FunctionId.NEXT) }
            val frame = idle.render(1) as ProjectionFrame.MenuScreen
            repeat(index) { idle.onInput(com.opensab.sab.protocol.assignment.FunctionId.PREVIOUS) }
            // The weather entry with no forecast falls back to NO_WEATHER rather than to the
            // feature icon, as `MainWeatherPageItem` does; what matters here is that it keeps an
            // icon at all.
            val expected =
                if (item == RootMenuItem.WEATHER) IconId.NO_WEATHER else item.iconId
            assertEquals(
                "the ${item.name} entry has no second line, so it may and must keep its icon",
                expected,
                frame.contentIconId
            )
        }
    }

    /**
     * The music entry playing a track with no artist: the title fills line 1, line 2 stays empty
     * and the icon comes back. Nothing on the wire forbids that shape — `VolumePage` is the same —
     * and it is the case that made the rule worth pinning rather than special-casing per entry.
     */
    @Test
    fun `should keep the icon when a track has no artist`() {
        val menu = RootMenuPageProvider(
            entries = allRootMenuEntries(),
            musicState = MutableStateFlow(
                MusicState(
                    metadata = TrackMetadata(title = "", artist = "Earl Sweatshirt"),
                    status = PlaybackStatus.PLAYING
                )
            ),
            pageFactory = { _ -> error("no entry is opened here") },
            copy = englishClusterText
        )
        repeat(4) { menu.onInput(com.opensab.sab.protocol.assignment.FunctionId.NEXT) }

        val frame = menu.render(1) as ProjectionFrame.MenuScreen
        assertEquals(RootMenuItem.MUSIC, menu.selectedItem)
        assertFalse(
            "an artist with no title still fills line 2, so the icon has to go",
            frame.contentIconId != IconId.IC_NONE && !frame.contentTextLine2.isNullOrEmpty()
        )
    }
}
