package com.opensab.cluster.rootmenu

import app.cash.turbine.test
import com.opensab.cluster.CuingPage
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.allRootMenuEntries
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.audio.Cue
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallStatus
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.nav.ManeuverType
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.settings.RootMenuEntry
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechQuantity
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.speech.SpeechWord
import com.opensab.domain.weather.WeatherConditionType
import com.opensab.domain.weather.WeatherInfo
import com.opensab.domain.weather.WmoCaption
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Root menu behavior.
 *
 * PREVIOUS moves the cursor up, NEXT moves it down, SELECT enters the item, BACK leaves the page and
 * MENU returns to the root. The cursor clamps instead of wrapping, and the arrows are
 * (left = false, right = true, up = !isFirst, down = !isLast).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootMenuPageProviderTest {

    private fun provider(
        items: List<RootMenuItem> = RootMenuItem.entries,
        musicPage: PageProvider? = null,
        musicLoadingPage: PageProvider? = null,
        volumePage: PageProvider? = null,
        navPage: PageProvider? = null,
        callPage: PageProvider? = null,
        weatherPage: PageProvider? = null,
        messagePage: PageProvider? = null,
        messageMenuPage: PageProvider? = null,
        musicState: StateFlow<com.opensab.domain.music.MusicState>? = null,
        navInstruction: StateFlow<NavInstruction?>? = null,
        activeCall: StateFlow<CallInfo?>? = null,
        currentWeather: StateFlow<WeatherInfo?>? = null,
        currentMessage: StateFlow<MessageInfo?>? = null
    ) = RootMenuPageProvider(
        entries = MutableStateFlow(items.map { RootMenuEntry.valueOf(it.name) }),
        musicState = musicState,
        navInstruction = navInstruction,
        activeCall = activeCall,
        currentWeather = currentWeather,
        currentMessage = currentMessage,
        pageFactory = { item ->
            val wired = when (item) {
                RootMenuItem.MUSIC -> musicPage
                RootMenuItem.VOLUME -> volumePage
                RootMenuItem.NAVIGATION -> navPage
                RootMenuItem.CALL -> callPage
                RootMenuItem.WEATHER -> weatherPage
                RootMenuItem.MESSAGE -> messagePage
            }
            // Every entry opens something; a test only names the page whose identity it asserts.
            wired ?: StubPageProvider(item.title.name, ProjectionFrame.RequestSabMode(1))
        },
        copy = englishClusterText,
        musicLoadingPageFactory = musicLoadingPage?.let { { it } },
        messageMenuPageFactory = messageMenuPage?.let { { it } }
    )

    @Test
    fun rendersTheSelectedItemTitleInTheMenuHeader() {
        val frame = provider().render(requestId = 0x08)

        assertTrue("Root page must project a MENU body", frame is ProjectionFrame.MenuScreen)
        val menu = frame as ProjectionFrame.MenuScreen
        assertEquals(0x08, menu.requestId)
        assertEquals(englishClusterText.resolve(RootMenuItem.entries.first().title), menu.headerText)
        // The first entry is WEATHER, which renders NO_WEATHER until a forecast arrives.
        assertEquals(IconId.NO_WEATHER, menu.contentIconId)
    }

    @Test
    fun movesTheCursorDownWhenNextIsPressed() {
        val page = provider()

        val action = page.onInput(FunctionId.NEXT)

        assertEquals(1, page.selectionIndex)
        assertEquals(NavigationAction.Invalidate, action)
        assertEquals(
            englishClusterText.resolve(RootMenuItem.entries[1].title),
            (page.render(1) as ProjectionFrame.MenuScreen).headerText
        )
    }

    @Test
    fun movesTheCursorUpWhenPreviousIsPressed() {
        val page = provider()
        page.onInput(FunctionId.NEXT)

        page.onInput(FunctionId.PREVIOUS)

        assertEquals(0, page.selectionIndex)
    }

    @Test
    fun clampsTheCursorAtTheLastItem() {
        val page = provider()
        repeat(RootMenuItem.entries.size + 3) { page.onInput(FunctionId.NEXT) }

        assertEquals(RootMenuItem.entries.lastIndex, page.selectionIndex)
    }

    @Test
    fun clampsTheCursorAtTheFirstItem() {
        val page = provider()
        repeat(3) { page.onInput(FunctionId.PREVIOUS) }

        assertEquals(0, page.selectionIndex)
    }

    @Test
    fun hidesTheUpArrowOnTheFirstItemAndTheDownArrowOnTheLastItem() {
        val page = provider(
            currentWeather = MutableStateFlow(
                WeatherInfo(
                    locationName = "Valencia",
                    temperature = 28,
                    temperatureUnit = "°C",
                    caption = WmoCaption.CLEAR,
                    type = WeatherConditionType.SUNNY
                )
            )
        )

        val first = page.render(1) as ProjectionFrame.MenuScreen
        assertEquals(IconId.IC_NONE, first.upActionIconId)
        assertEquals(IconId.MENU_ARROW_DOWN, first.downActionIconId)
        assertEquals(IconId.IC_NONE, first.leftActionIconId)
        assertEquals(IconId.MENU_ARROW_RIGHT, first.rightActionIconId)

        repeat(RootMenuItem.entries.size) { page.onInput(FunctionId.NEXT) }
        val last = page.render(2) as ProjectionFrame.MenuScreen
        assertEquals(IconId.MENU_ARROW_UP, last.upActionIconId)
        assertEquals(IconId.IC_NONE, last.downActionIconId)
    }

    @Test
    fun pushesTheMusicPageWhenSelectIsPressedOnTheMusicEntry() {
        val musicPage = StubPageProvider("music", ProjectionFrame.RequestSabMode(1))
        val page = provider(musicPage = musicPage)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { page.onInput(FunctionId.NEXT) }

        val action = page.onInput(FunctionId.SELECT)

        assertTrue("SELECT on the music entry must push a page", action is NavigationAction.Next)
        assertSame(musicPage, (action as NavigationAction.Next).page)
    }

    @Test
    fun routesMusicToLoadingPageWhenColdAndLivePageWhenWarm() {
        val coldState = MutableStateFlow(com.opensab.domain.music.MusicState(status = com.opensab.domain.music.PlaybackStatus.NONE))
        val livePage = StubPageProvider("MusicPage", ProjectionFrame.RequestSabMode(1))
        val loadingPage = StubPageProvider("MusicLoadingPage", ProjectionFrame.RequestSabMode(2))

        val coldPage = provider(
            musicState = coldState,
            musicPage = livePage,
            musicLoadingPage = loadingPage
        )
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { coldPage.onInput(FunctionId.NEXT) }
        val coldAction = coldPage.onInput(FunctionId.SELECT)
        assertTrue(coldAction is NavigationAction.Next)
        assertSame(loadingPage, (coldAction as NavigationAction.Next).page)

        val warmState = MutableStateFlow(com.opensab.domain.music.MusicState(status = com.opensab.domain.music.PlaybackStatus.PLAYING))
        val warmPage = provider(
            musicState = warmState,
            musicPage = livePage,
            musicLoadingPage = loadingPage
        )
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { warmPage.onInput(FunctionId.NEXT) }
        val warmAction = warmPage.onInput(FunctionId.SELECT)
        assertTrue(warmAction is NavigationAction.Next)
        assertSame(livePage, (warmAction as NavigationAction.Next).page)
    }

    @Test
    fun resetsToTheRootWhenMenuIsPressed() {
        val page = provider()

        assertEquals(NavigationAction.Reset, page.onInput(FunctionId.MENU))
    }

    @Test
    fun retainsItsStateWhenResumed() {
        assertEquals(ResumeBehavior.RETAIN, provider().onResumeBehavior)
    }

    @Test
    fun rendersStaticIconAndNoTextOnVolumeMenuItem() {
        val page = provider()
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.VOLUME)) { page.onInput(FunctionId.NEXT) }

        val frame = page.render(42) as ProjectionFrame.MenuScreen
        assertEquals("Volume", frame.headerText)
        assertEquals(IconId.VOLUME, frame.contentIconId)
        assertEquals("", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun pushesVolumePageWhenSelectPressedOnVolumeItem() {
        val volumePage = StubPageProvider("volume", ProjectionFrame.RequestSabMode(1))
        val page = provider(volumePage = volumePage)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.VOLUME)) { page.onInput(FunctionId.NEXT) }

        val action = page.onInput(FunctionId.SELECT)
        assertTrue(action is NavigationAction.Next)
        assertSame(volumePage, (action as NavigationAction.Next).page)
    }

    @Test
    fun canonicalMenuItemOrder() {
        assertEquals(
            listOf(
                RootMenuItem.WEATHER,
                RootMenuItem.NAVIGATION,
                RootMenuItem.CALL,
                RootMenuItem.MESSAGE,
                RootMenuItem.MUSIC,
                RootMenuItem.VOLUME
            ),
            RootMenuItem.entries
        )
    }

    @Test
    fun rendersDynamicManeuverIconAndDistanceOnNavigationMenuItemWhenActive() {
        val navInstruction = MutableStateFlow<NavInstruction?>(
            NavInstruction(
                title = "Turn Left",
                streetName = "Gran Via",
                distanceFormatted = "400 m",
                maneuver = ManeuverType.TURN_LEFT
            )
        )
        val page = provider(navInstruction = navInstruction)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.NAVIGATION)) { page.onInput(FunctionId.NEXT) }
        val frame = page.render(50) as ProjectionFrame.MenuScreen
        assertEquals("Navigation", frame.headerText)
        assertEquals(IconId.DA_TURN_LEFT, frame.contentIconId)
        assertEquals("400 m", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun rendersStaticNavigationIconWhenInactive() {
        val page = provider(navInstruction = MutableStateFlow(null))
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.NAVIGATION)) { page.onInput(FunctionId.NEXT) }
        val frame = page.render(51) as ProjectionFrame.MenuScreen
        assertEquals(IconId.NAVIGATION, frame.contentIconId)
        assertEquals("", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun pushesNavigationPageWhenSelectPressedOnNavigationItem() {
        val navPage = StubPageProvider("nav", ProjectionFrame.RequestSabMode(1))
        val page = provider(navPage = navPage)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.NAVIGATION)) { page.onInput(FunctionId.NEXT) }

        val action = page.onInput(FunctionId.SELECT)
        assertTrue(action is NavigationAction.Next)
        assertSame(navPage, (action as NavigationAction.Next).page)
    }

    @Test
    fun rendersCallerNameAndStatusOnCallMenuItemWhenActiveWithoutIcon() {
        val activeCall = MutableStateFlow<CallInfo?>(
            CallInfo(
                callerName = "David",
                callerNumber = "+34655443322",
                status = CallStatus.ACTIVE
            )
        )
        val page = provider(activeCall = activeCall)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.CALL)) { page.onInput(FunctionId.NEXT) }

        val frame = page.render(60) as ProjectionFrame.MenuScreen
        assertEquals("Phone", frame.headerText)
        assertEquals(IconId.IC_NONE, frame.contentIconId)
        assertEquals("David", frame.contentTextLine1)
        assertEquals("In Call", frame.contentTextLine2)
    }

    @Test
    fun rendersStaticCallIconWhenInactive() {
        val page = provider(activeCall = MutableStateFlow(null))
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.CALL)) { page.onInput(FunctionId.NEXT) }
        val frame = page.render(61) as ProjectionFrame.MenuScreen
        assertEquals(IconId.CALL, frame.contentIconId)
        assertEquals("", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun pushesCallPageWhenSelectPressedOnCallItem() {
        val callPage = StubPageProvider("call", ProjectionFrame.RequestSabMode(1))
        val page = provider(callPage = callPage)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.CALL)) { page.onInput(FunctionId.NEXT) }

        val action = page.onInput(FunctionId.SELECT)
        assertTrue(action is NavigationAction.Next)
        assertSame(callPage, (action as NavigationAction.Next).page)
    }

    @Test
    fun rendersDynamicWeatherIconAndTemperatureOnWeatherMenuItemWhenAvailable() {
        val currentWeather = MutableStateFlow<WeatherInfo?>(
            WeatherInfo(
                locationName = "Valencia",
                temperature = 28,
                temperatureUnit = "°C",
                caption = WmoCaption.CLEAR,
                type = WeatherConditionType.SUNNY
            )
        )
        val page = provider(currentWeather = currentWeather)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.WEATHER)) { page.onInput(FunctionId.NEXT) }

        val frame = page.render(70) as ProjectionFrame.MenuScreen
        assertEquals("Weather", frame.headerText)
        assertEquals(IconId.WEATHER_SUNNY, frame.contentIconId)
        assertEquals("28°C", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun rendersNoWeatherIconButKeepsTheRightArrowWhenInactive() {
        val page = provider(currentWeather = MutableStateFlow(null))
        val frame = page.render(71) as ProjectionFrame.MenuScreen
        assertEquals("Weather", frame.headerText)
        assertEquals(IconId.NO_WEATHER, frame.contentIconId)
        assertEquals(IconId.MENU_ARROW_RIGHT, frame.rightActionIconId)
        assertEquals("", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun rendersTheRightArrowOnTheWeatherItemOnceForecastDataArrives() {
        val currentWeather = MutableStateFlow<WeatherInfo?>(
            WeatherInfo(
                locationName = "Valencia",
                temperature = 28,
                temperatureUnit = "°C",
                caption = WmoCaption.CLEAR,
                type = WeatherConditionType.SUNNY
            )
        )
        val page = provider(currentWeather = currentWeather)
        val frame = page.render(72) as ProjectionFrame.MenuScreen
        assertEquals(IconId.MENU_ARROW_RIGHT, frame.rightActionIconId)
    }

    @Test
    fun rendersTheActiveMusicIconWhenPlayingWithoutTrackMetadata() {
        val musicState = MutableStateFlow(
            com.opensab.domain.music.MusicState(
                metadata = com.opensab.domain.music.TrackMetadata(),
                status = com.opensab.domain.music.PlaybackStatus.PLAYING
            )
        )
        val page = provider(musicState = musicState)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { page.onInput(FunctionId.NEXT) }

        val frame = page.render(73) as ProjectionFrame.MenuScreen
        assertEquals(IconId.ACTIVE_MUSIC, frame.contentIconId)
    }

    /**
     * Without a reading the weather page is the only place the rider can ask for one: its SELECT
     * refreshes the source. Closing the entry until a forecast arrived left no way out of a blank
     * carousel slot on a phone that had no fix at session start (`WEATHER-NO-FIX`).
     */
    @Test
    fun opensTheWeatherPageWithoutAForecastSoTheRiderCanAskForOne() {
        val weatherPage = StubPageProvider("weather", ProjectionFrame.RequestSabMode(1))
        val page = provider(weatherPage = weatherPage, currentWeather = MutableStateFlow(null))
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.WEATHER)) { page.onInput(FunctionId.NEXT) }

        val action = page.onInput(FunctionId.SELECT)
        assertTrue(action is NavigationAction.Next)
        assertSame(weatherPage, (action as NavigationAction.Next).page)
    }

    @Test
    fun pushesTheWeatherPageOnceForecastDataArrives() {
        val weatherPage = StubPageProvider("weather", ProjectionFrame.RequestSabMode(1))
        val currentWeather = MutableStateFlow<WeatherInfo?>(
            WeatherInfo(
                locationName = "Valencia",
                temperature = 28,
                temperatureUnit = "°C",
                caption = WmoCaption.CLEAR,
                type = WeatherConditionType.SUNNY
            )
        )
        val page = provider(weatherPage = weatherPage, currentWeather = currentWeather)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.WEATHER)) { page.onInput(FunctionId.NEXT) }

        val action = page.onInput(FunctionId.SELECT)
        assertTrue(action is NavigationAction.Next)
        assertSame(weatherPage, (action as NavigationAction.Next).page)
    }

    @Test
    fun mirrorKeepsTheOpenZoneEnabledOnTheWeatherEntryWithoutAForecast() {
        val weatherPage = StubPageProvider("weather", ProjectionFrame.RequestSabMode(1))
        val page = provider(weatherPage = weatherPage, currentWeather = MutableStateFlow(null))
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.WEATHER)) { page.onInput(FunctionId.NEXT) }

        assertTrue(openAction(page).enabled)
    }

    @Test
    fun rendersStaticIconAndNoTextOnMessageMenuItem() {
        val page = provider()
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MESSAGE)) { page.onInput(FunctionId.NEXT) }

        val frame = page.render(80) as ProjectionFrame.MenuScreen
        assertEquals("Messages", frame.headerText)
        assertEquals(IconId.MESSAGE, frame.contentIconId)
        assertEquals("", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun rendersSenderAndBodyOnMessageMenuItemWhileAMessageIsUnread() {
        val page = provider(
            currentMessage = MutableStateFlow(MessageInfo(sender = "Elena", body = "Are you far?"))
        )
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MESSAGE)) { page.onInput(FunctionId.NEXT) }

        val frame = page.render(81) as ProjectionFrame.MenuScreen
        assertEquals("Messages", frame.headerText)
        // And no content icon with them: the BTU rejected `IconId.MESSAGE` here eight times in the
        // field — `status=0x0C` at the `contentIconId` byte — leaving the rider with a carousel
        // that skips from Phone to Music (`RootMenuContentIconTest`).
        assertEquals(IconId.IC_NONE, frame.contentIconId)
        assertEquals("Elena", frame.contentTextLine1)
        assertEquals("Are you far?", frame.contentTextLine2)
    }

    /**
     * Outbound message compose: the entry is permanently open (`canEnter = true`).
     * With nothing unread and messageMenuPageFactory wired, SELECT routes to the compose menu page,
     * the right arrow is shown and the OPEN action in mirror is enabled.
     */
    @Test
    fun allowsMessageEntryWhileNothingIsUnreadAndRoutesToMessageMenu() {
        val messagePage = StubPageProvider("message", ProjectionFrame.RequestSabMode(1))
        val messageMenuPage = StubPageProvider("messageMenu", ProjectionFrame.RequestSabMode(2))
        val page = provider(
            messagePage = messagePage,
            messageMenuPage = messageMenuPage,
            currentMessage = MutableStateFlow(null)
        )
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MESSAGE)) { page.onInput(FunctionId.NEXT) }

        val action = page.onInput(FunctionId.SELECT)
        assertTrue("SELECT on MESSAGE entry must push a page", action is NavigationAction.Next)
        assertSame(messageMenuPage, (action as NavigationAction.Next).page)
        assertEquals(
            IconId.MENU_ARROW_RIGHT,
            (page.render(82) as ProjectionFrame.MenuScreen).rightActionIconId
        )
        assertTrue(openAction(page).enabled)
    }

    @Test
    fun routesToReadPageWhenMessageIsPresentEvenWithMenuPageConfigured() {
        val messagePage = StubPageProvider("message", ProjectionFrame.RequestSabMode(1))
        val messageMenuPage = StubPageProvider("messageMenu", ProjectionFrame.RequestSabMode(2))
        val page = provider(
            messagePage = messagePage,
            messageMenuPage = messageMenuPage,
            currentMessage = MutableStateFlow(MessageInfo(sender = "Elena", body = "Are you far?"))
        )
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MESSAGE)) { page.onInput(FunctionId.NEXT) }

        val action = page.onInput(FunctionId.SELECT)
        assertTrue(action is NavigationAction.Next)
        assertSame(messagePage, (action as NavigationAction.Next).page)
    }

    @Test
    fun refusesTheMessageEntryWhileNothingIsUnreadAndNoMenuPageConfigured() {
        val messagePage = StubPageProvider("message", ProjectionFrame.RequestSabMode(1))
        val page = provider(
            messagePage = messagePage,
            messageMenuPage = null,
            currentMessage = MutableStateFlow(null)
        )
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MESSAGE)) { page.onInput(FunctionId.NEXT) }

        assertEquals(NavigationAction.None, page.onInput(FunctionId.SELECT))
        assertEquals(
            IconId.IC_NONE,
            (page.render(82) as ProjectionFrame.MenuScreen).rightActionIconId
        )
        assertFalse(openAction(page).enabled)
    }

    @Test
    fun pushesMessagePageWhenSelectPressedOnMessageItem() {
        val messagePage = StubPageProvider("message", ProjectionFrame.RequestSabMode(1))
        val page = provider(
            messagePage = messagePage,
            currentMessage = MutableStateFlow(MessageInfo(sender = "Elena", body = "Are you far?"))
        )
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MESSAGE)) { page.onInput(FunctionId.NEXT) }

        val action = page.onInput(FunctionId.SELECT)
        assertTrue(action is NavigationAction.Next)
        assertSame(messagePage, (action as NavigationAction.Next).page)
        assertEquals(
            IconId.MENU_ARROW_RIGHT,
            (page.render(83) as ProjectionFrame.MenuScreen).rightActionIconId
        )
        assertTrue(openAction(page).enabled)
    }

    /** A message arriving must redraw the carousel that is already mounted. */
    @Test
    fun invalidatesWhenAMessageArrives() = runTest {
        val currentMessage = MutableStateFlow<MessageInfo?>(null)
        val page = provider(currentMessage = currentMessage)
        val redraws = mutableListOf<Unit>()
        val job = launch { page.invalidations.collect { redraws += it } }
        delay(50.milliseconds)

        currentMessage.value = MessageInfo(sender = "Elena", body = "Are you far?")
        delay(50.milliseconds)
        job.cancel()

        assertEquals("the carousel must redraw exactly once", 1, redraws.size)
    }

    /**
     * Every icon the root menu projects must carry its tint, and every absent icon must omit it.
     * The tint byte is only on the wire when its icon is present: the presence rule *is* the grammar
     * the BTU parses with. Omitting one tint shortens the frame and shifts every later field.
     */
    @Test
    fun tintsEveryIconItProjectsSoTheBtuKeepsTheFieldOffsets() {
        val page = provider()

        RootMenuItem.entries.forEachIndexed { index, item ->
            val frame = page.render(index) as ProjectionFrame.MenuScreen
            val pairs = listOf(
                "up" to (frame.upActionIconId to frame.upActionIconTintColorId),
                "down" to (frame.downActionIconId to frame.downActionIconTintColorId),
                "left" to (frame.leftActionIconId to frame.leftActionIconTintColorId),
                "right" to (frame.rightActionIconId to frame.rightActionIconTintColorId),
                "content" to (frame.contentIconId to frame.contentIconTintColorId)
            )
            pairs.forEach { (name, pair) ->
                val (iconId, tint) = pair
                assertEquals(
                    "$item: the $name tint must be present exactly when its icon is",
                    iconId != IconId.IC_NONE,
                    tint != null
                )
            }
            page.onInput(FunctionId.NEXT)
        }
    }

    /** The four menu arrows are tinted PURE_BLACK and the content icon PURE_WHITE. */
    @Test
    fun usesTheStandardTintsForTheArrowsAndTheContentIcon() {
        val page = provider()
        page.onInput(FunctionId.NEXT) // NAVIGATION: up, down, right and a content icon are all shown.

        val frame = page.render(0x10) as ProjectionFrame.MenuScreen
        assertEquals(ColorId.PURE_BLACK, frame.upActionIconTintColorId)
        assertEquals(ColorId.PURE_BLACK, frame.downActionIconTintColorId)
        assertEquals(ColorId.PURE_BLACK, frame.rightActionIconTintColorId)
        assertEquals(ColorId.PURE_WHITE, frame.contentIconTintColorId)
    }

    /**
     * Field regression: the BTU rejected the music item with
     * `status=0x0C, errorPosition=18` four times while a track was playing. With every tint
     * omitted the frame was 3 bytes short, so the BTU read the line1 length as the right-arrow
     * icon and the first character of the track title as its tint — offset 18.
     */
    @Test
    fun encodesTheMusicItemWithATrackTitleWithoutShiftingTheFieldsAfterTheArrows() {
        val musicState = MutableStateFlow(
            com.opensab.domain.music.MusicState(
                metadata = com.opensab.domain.music.TrackMetadata(title = "Ride", artist = "Ana"),
                status = com.opensab.domain.music.PlaybackStatus.PLAYING
            )
        )
        val page = provider(musicState = musicState)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MUSIC)) { page.onInput(FunctionId.NEXT) }

        val encoded = page.render(0x2A).encode()

        val expected = byteArrayOf(
            0x01, 0x2A, // MYSTERY_BOX, requestId
            ColorId.ORANGE.id.toByte(), // headerBgColorId = Feature.MUSIC color
            0x05, 0x4D, 0x75, 0x73, 0x69, 0x63, 0x00, // "Music" + PURE_WHITE
            0x01, // bodyType MENU
            ColorId.ORANGE_LIGHT.id.toByte(), // bodyBgColorId = Feature.MUSIC colorLight
            IconId.MENU_ARROW_UP.id.toByte(), ColorId.PURE_BLACK.id.toByte(),
            IconId.MENU_ARROW_DOWN.id.toByte(), ColorId.PURE_BLACK.id.toByte(),
            IconId.IC_NONE.id.toByte(), // left arrow hidden, tint omitted
            IconId.MENU_ARROW_RIGHT.id.toByte(), ColorId.PURE_BLACK.id.toByte(),
            IconId.IC_NONE.id.toByte(), // content icon yields to the track title, tint omitted
            0x04, 0x52, 0x69, 0x64, 0x65, ColorId.PURE_WHITE.id.toByte(), // "Ride"
            0x03, 0x41, 0x6E, 0x61, ColorId.GREY_LIGHT.id.toByte() // "Ana"
        )
        assertArrayEquals(expected, encoded)
    }

    /**
     * The MENU header takes the feature's `color` and the body its `colorLight`. Sending the `_DARK`
     * shade in the header and PURE_BLACK in the body made the cluster paint a black screen with a
     * near-black strip, on a `TFT_NS_5INCH_24YM_TYPE1` whose capabilities say `color=true` — the
     * panel was willing, the frame was not.
     */
    @Test
    fun paintsEachItemWithItsFeatureColourPair() {
        val expected = mapOf(
            RootMenuItem.WEATHER to (ColorId.GREY to ColorId.GREY_LIGHT),
            RootMenuItem.NAVIGATION to (ColorId.GREEN to ColorId.GREEN_LIGHT),
            RootMenuItem.CALL to (ColorId.PURPLE to ColorId.PURPLE_LIGHT),
            RootMenuItem.MESSAGE to (ColorId.BLUE to ColorId.BLUE_LIGHT),
            RootMenuItem.MUSIC to (ColorId.ORANGE to ColorId.ORANGE_LIGHT),
            RootMenuItem.VOLUME to (ColorId.GREY to ColorId.GREY_LIGHT)
        )
        val page = provider()

        RootMenuItem.entries.forEachIndexed { index, item ->
            val frame = page.render(index) as ProjectionFrame.MenuScreen
            val (header, body) = expected.getValue(item)
            assertEquals("$item header background", header, frame.headerBgColorId)
            assertEquals("$item body background", body, frame.bodyBgColorId)
            page.onInput(FunctionId.NEXT)
        }
    }

    /**
     * PURE_BLACK is one of the two fixed cluster colours the assignment never registers: it is
     * literally "no colour". No carousel entry may reach the cluster with it as a background.
     */
    @Test
    fun neverPaintsACarouselEntryOnTheColourlessFixedBackground() {
        val page = provider()

        RootMenuItem.entries.forEachIndexed { index, item ->
            val frame = page.render(index) as ProjectionFrame.MenuScreen
            assertNotEquals("$item header background", ColorId.PURE_BLACK, frame.headerBgColorId)
            assertNotEquals("$item body background", ColorId.PURE_BLACK, frame.bodyBgColorId)
            page.onInput(FunctionId.NEXT)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Mirror
    // -------------------------------------------------------------------------------------------

    /**
     * The four zones the carousel offers, each carrying the `FunctionId` its `onInput` branch acts
     * on: PREVIOUS and NEXT move the cursor, SELECT enters the entry and MENU resets to the root.
     * `HEADER` is reserved for that escape, so the three moving zones keep UP, DOWN and PRIMARY.
     */
    @Test
    fun mirrorsTheFourCarouselActions() {
        val currentWeather = MutableStateFlow<WeatherInfo?>(
            WeatherInfo(
                locationName = "Valencia",
                temperature = 28,
                temperatureUnit = "°C",
                caption = WmoCaption.CLEAR,
                type = WeatherConditionType.SUNNY
            )
        )
        val page = provider(
            weatherPage = StubPageProvider("weather", ProjectionFrame.RequestSabMode(1)),
            currentWeather = currentWeather
        )

        val mirror = page.mirror()

        assertEquals("RootMenu", mirror.pageName)
        assertEquals(
            listOf(
                MirrorAction(
                    MirrorActionToken.CAROUSEL_PREVIOUS,
                    FunctionId.PREVIOUS,
                    MirrorSlot.UP
                ),
                MirrorAction(MirrorActionToken.CAROUSEL_NEXT, FunctionId.NEXT, MirrorSlot.DOWN),
                MirrorAction(MirrorActionToken.OPEN, FunctionId.SELECT, MirrorSlot.PRIMARY),
                MirrorAction(MirrorActionToken.OPEN_ROOT_MENU, FunctionId.MENU, MirrorSlot.HEADER)
            ),
            mirror.actions
        )
    }

    /**
     * `OPEN` is the one disabled action of this page, and it is disabled on purpose: while
     * `canEnter` says no, `SELECT` returns `None`, so the zone holds its slot while showing the
     * rider that right now it leads nowhere instead of vanishing and shifting the others.
     *
     * The cause is the one that happens on the motorcycle — a source that has gone quiet — and not
     * an entry wired without a page, which is impossible since the factory is total.
     */
    @Test
    fun disablesTheOpenActionWhileTheSelectedEntryCannotBeEntered() {
        val page = provider(currentMessage = MutableStateFlow(null))

        // MESSAGE with no unread message and no compose menu wired leads nowhere.
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.MESSAGE)) { page.onInput(FunctionId.NEXT) }
        assertFalse(openAction(page).enabled)

        page.onInput(FunctionId.NEXT)
        assertTrue(openAction(page).enabled)
    }

    /**
     * The root menu takes no `volumeSource`: its `onInput` sends every other `FunctionId` to the
     * `else` branch, which returns `None`. Declaring a volume zone here would paint a control that
     * does nothing. Nothing is aliased
     * either, because no two `FunctionId` share a branch on this page.
     */
    @Test
    fun declaresNoVolumeActionsBecauseTheRootMenuOwnsNoVolumeSource() {
        val actions = provider().mirror().actions

        val volumeFunctions = setOf(
            FunctionId.VOLUME_UP,
            FunctionId.VOLUME_DOWN,
            FunctionId.VOLUME_MUTE
        )
        assertTrue(
            "the root menu must not mirror a volume zone",
            actions.none { it.functionId in volumeFunctions || it.aliases.any { id -> id in volumeFunctions } }
        )
        assertTrue("no branch of the root menu is shared", actions.all { it.aliases.isEmpty() })
    }

    /**
     * The mirror shows the very strings the cluster received for the selected entry, so a page
     * cannot change what it projects and forget to change what it mirrors.
     */
    @Test
    fun mirrorsTheProjectedHeaderAndContentLinesOfTheSelectedEntry() {
        val activeCall = MutableStateFlow<CallInfo?>(
            CallInfo(
                callerName = "David",
                callerNumber = "+34655443322",
                status = CallStatus.ACTIVE
            )
        )
        val page = provider(activeCall = activeCall)
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.CALL)) { page.onInput(FunctionId.NEXT) }

        val mirror = page.mirror()
        val frame = page.render(0) as ProjectionFrame.MenuScreen

        assertEquals(frame.headerText, mirror.title)
        assertEquals(listOf("David", "In Call"), mirror.lines)
        assertEquals(frame.headerBgColorId, mirror.accent)
    }

    /** Empty content lines are the absence of a line, not a blank one to paint. */
    @Test
    fun mirrorsNoContentLinesOnAnEntryThatProjectsNone() {
        val page = provider()
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.VOLUME)) { page.onInput(FunctionId.NEXT) }

        val mirror = page.mirror()

        assertEquals("Volume", mirror.title)
        assertEquals(emptyList<String>(), mirror.lines)
    }

    /**
     * Building a page is how the carousel *opens* an entry, never how it answers a question about
     * one. [PageProvider.mirror] runs on every projected frame, so a page built in there is a page
     * built and thrown away at the rate of whichever source ticks fastest.
     *
     * The witness is the factory counting itself, which is as direct as it gets: no clock, no
     * doubles and no knowledge of what any particular page does on the way up.
     */
    @Test
    fun mirroringTheCarouselBuildsNoPage() {
        var built = 0
        val page = countingProvider { built++ }

        repeat(50) { page.mirror() }

        assertEquals("mirroring the carousel must build no page", 0, built)
    }

    /**
     * The pin on the other half of the projection path: `render` never called the factory, and this
     * is what stops it from starting to.
     */
    @Test
    fun renderingTheCarouselBuildsNoPage() {
        var built = 0
        val page = countingProvider { built++ }

        repeat(50) { page.render(1) }

        assertEquals("rendering the carousel must build no page", 0, built)
    }

    /**
     * The other half of the rule: the factory *is* called, exactly once, and only from the one
     * place that is allowed to call it — the SELECT branch of [RootMenuPageProvider.onInput], and
     * only once `canEnter` has already said yes.
     */
    @Test
    fun selectBuildsExactlyOnePage() {
        var built = 0
        val page = countingProvider { built++ }

        page.onInput(FunctionId.SELECT)

        assertEquals("a deliberate press must build exactly one page", 1, built)
    }

    /**
     * A carousel whose factory reports every call, so the tests above can count pages built rather
     * than reason about what building one would cost.
     *
     * The forecast is not decoration: the cursor starts on `WEATHER`, and without it `canEnter` is
     * false, which short-circuits the whole question before the factory is ever reached. The entry
     * under the cursor has to be one the rider can actually open, or the witness counts nothing and
     * says so.
     */
    private fun countingProvider(onBuild: () -> Unit) = RootMenuPageProvider(
        entries = allRootMenuEntries(),
        currentWeather = MutableStateFlow(
            WeatherInfo(
                locationName = "Valencia",
                temperature = 28,
                temperatureUnit = "°C",
                caption = WmoCaption.CLEAR,
                type = WeatherConditionType.SUNNY
            )
        ),
        pageFactory = {
            onBuild()
            StubPageProvider("stub", ProjectionFrame.RequestSabMode(1))
        },
        copy = englishClusterText
    )

    private fun openAction(page: RootMenuPageProvider): MirrorAction =
        page.mirror().actions.single { it.token == MirrorActionToken.OPEN }

    // -------------------------------------------------------------------------------------------
    // Cues
    // -------------------------------------------------------------------------------------------

    private fun TestScope.cuesOf(page: CuingPage): MutableList<Cue> {
        val heard = mutableListOf<Cue>()
        backgroundScope.launch { page.cues.collect { heard += it } }
        runCurrent()
        return heard
    }

    @Test
    fun `should cue every move of the cursor`() = runTest {
        val page = provider()
        val heard = cuesOf(page)

        page.onInput(FunctionId.NEXT)
        page.onInput(FunctionId.NEXT)
        page.onInput(FunctionId.PREVIOUS)
        runCurrent()

        assertEquals(listOf(Cue.MOVE, Cue.MOVE, Cue.MOVE), heard)
    }

    @Test
    fun `should cue the end of the list instead of a move`() = runTest {
        val page = provider()
        val heard = cuesOf(page)

        page.onInput(FunctionId.PREVIOUS) // already at the top
        repeat(RootMenuItem.entries.lastIndex) { page.onInput(FunctionId.NEXT) } // walk to the bottom
        page.onInput(FunctionId.NEXT) // already at the bottom

        runCurrent()

        val moves = List(RootMenuItem.entries.lastIndex) { Cue.MOVE }
        assertEquals(listOf(Cue.EDGE) + moves + listOf(Cue.EDGE), heard)
    }

    @Test
    fun `should stay quiet on the keys that are not the cursor`() = runTest {
        val page = provider()
        val heard = cuesOf(page)

        page.onInput(FunctionId.SELECT)
        page.onInput(FunctionId.MENU)
        runCurrent()

        assertEquals(emptyList<Cue>(), heard)
    }

    // -------------------------------------------------------------------------------------------
    // Copy
    // -------------------------------------------------------------------------------------------

    /**
     * The page names the line; somebody else says it in a language. What this checks is not the
     * translation — that lives in `values-es/` and is checked over the XML — but that the provider
     * does not invent the string, which is the half a Spanish phone would show in English.
     */
    @Test
    fun `should take every carousel header from the resolver`() {
        val page = RootMenuPageProvider(
            entries = allRootMenuEntries(),
            pageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
            copy = ClusterTextResolver { "[${it.name}]" }
        )

        val headers = RootMenuItem.entries.indices.map { index ->
            repeat(index) { page.onInput(FunctionId.NEXT) }
            val header = (page.render(1) as ProjectionFrame.MenuScreen).headerText
            repeat(index) { page.onInput(FunctionId.PREVIOUS) }
            header
        }

        assertEquals(
            listOf(
                "[HEADER_WEATHER]", "[HEADER_NAVIGATION]", "[HEADER_PHONE]",
                "[HEADER_MESSAGES]", "[HEADER_MUSIC]", "[HEADER_VOLUME]"
            ),
            headers
        )
    }

    /**
     * The three lines the carousel words itself about a call: the name of the page standing in for
     * a caller with neither name nor number, and the two status lines. What the caller *is* called
     * is data and reaches the frame as it arrived.
     */
    @Test
    fun `should take the call lines it words itself from the resolver`() {
        val activeCall = MutableStateFlow<CallInfo?>(
            CallInfo(callerName = "", callerNumber = "", status = CallStatus.RINGING)
        )
        val page = RootMenuPageProvider(
            entries = allRootMenuEntries(),
            activeCall = activeCall,
            pageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
            copy = ClusterTextResolver { "[${it.name}]" }
        )
        repeat(RootMenuItem.entries.indexOf(RootMenuItem.CALL)) { page.onInput(FunctionId.NEXT) }

        val ringing = page.render(1) as ProjectionFrame.MenuScreen
        assertEquals("[HEADER_PHONE]", ringing.headerText)
        assertEquals("[HEADER_PHONE]", ringing.contentTextLine1)
        assertEquals("[CALL_RINGING]", ringing.contentTextLine2)

        activeCall.value = CallInfo(callerName = "", callerNumber = "", status = CallStatus.ACTIVE)

        assertEquals(
            "[CALL_IN_CALL]",
            (page.render(2) as ProjectionFrame.MenuScreen).contentTextLine2
        )
    }

    @Test
    fun speechEmitsScriptOnInitialMountMatchingCurrentItemAndLiveState() = runTest {
        val weather = MutableStateFlow<WeatherInfo?>(
            WeatherInfo(
                temperature = 21,
                temperatureUnit = "°C",
                type = WeatherConditionType.CLEAR,
                hourly = emptyList(),
                locationName = "Vigo"
            )
        )
        val page = provider(currentWeather = weather)
        page.speech.test {
            val script = awaitItem()
            assertEquals(1, script.size)
            assertEquals(SpeechToken.ROOT_WEATHER, script[0].token)
            assertEquals(
                listOf(SpeechArg.Word(SpeechWord.WEATHER_CLEAR), SpeechArg.Quantity(SpeechQuantity.DEGREES, 21)),
                script[0].args
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun speechEmitsNewScriptWhenCursorMovesWithPreviousOrNext() = runTest {
        val page = provider()
        page.speech.test {
            // initial item is WEATHER (with no weather data => ROOT_WEATHER_EMPTY)
            assertEquals(SpeechToken.ROOT_WEATHER_EMPTY, awaitItem().single().token)

            page.onInput(FunctionId.NEXT)
            // NAVIGATION without instruction => ROOT_NAVIGATION
            assertEquals(SpeechToken.ROOT_NAVIGATION, awaitItem().single().token)

            page.onInput(FunctionId.PREVIOUS)
            assertEquals(SpeechToken.ROOT_WEATHER_EMPTY, awaitItem().single().token)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun speechDoesNotEmitAtCarouselEdgeWhereCursorIsCoerced() = runTest {
        val page = provider()
        page.speech.test {
            assertEquals(SpeechToken.ROOT_WEATHER_EMPTY, awaitItem().single().token)

            // At index 0, PREVIOUS coerces to 0
            page.onInput(FunctionId.PREVIOUS)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun speechAnnouncesNeighboringItemWhenCurrentItemIsRemovedFromEntriesWhileCursorStationary() = runTest {
        val entriesFlow = MutableStateFlow(listOf(RootMenuEntry.WEATHER, RootMenuEntry.MUSIC))
        val page = RootMenuPageProvider(
            entries = entriesFlow,
            pageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
            copy = englishClusterText
        )
        page.speech.test {
            assertEquals(SpeechToken.ROOT_WEATHER_EMPTY, awaitItem().single().token)

            page.onInput(FunctionId.NEXT)
            assertEquals(SpeechToken.ROOT_MUSIC, awaitItem().single().token)

            // Remove MUSIC while stationary on index 1
            entriesFlow.value = listOf(RootMenuEntry.WEATHER)
            // Cursor falls back to index 0 (WEATHER)
            assertEquals(SpeechToken.ROOT_WEATHER_EMPTY, awaitItem().single().token)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun speechNamesTheEntryTheClusterPaintsWhenEntriesAreReorderedBeforeAnyMove() = runTest {
        val entriesFlow = MutableStateFlow(listOf(RootMenuEntry.WEATHER, RootMenuEntry.MUSIC))
        val page = RootMenuPageProvider(
            entries = entriesFlow,
            pageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
            copy = englishClusterText
        )
        page.speech.test {
            assertEquals(SpeechToken.ROOT_WEATHER_EMPTY, awaitItem().single().token)

            entriesFlow.value = listOf(RootMenuEntry.MUSIC, RootMenuEntry.WEATHER)

            assertEquals(SpeechToken.ROOT_MUSIC, awaitItem().single().token)
            assertEquals(
                englishClusterText.resolve(RootMenuItem.MUSIC.title),
                (page.render(1) as ProjectionFrame.MenuScreen).headerText
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun speechFollowsTheEntryItAnnouncedWhenEntriesAreReorderedAfterAMove() = runTest {
        val entriesFlow = MutableStateFlow(
            listOf(RootMenuEntry.WEATHER, RootMenuEntry.MUSIC, RootMenuEntry.VOLUME)
        )
        val page = RootMenuPageProvider(
            entries = entriesFlow,
            pageFactory = { StubPageProvider("stub", ProjectionFrame.RequestSabMode(1)) },
            copy = englishClusterText
        )
        page.speech.test {
            assertEquals(SpeechToken.ROOT_WEATHER_EMPTY, awaitItem().single().token)
            page.onInput(FunctionId.NEXT)
            assertEquals(SpeechToken.ROOT_MUSIC, awaitItem().single().token)

            entriesFlow.value = listOf(RootMenuEntry.VOLUME, RootMenuEntry.WEATHER, RootMenuEntry.MUSIC)

            expectNoEvents()
            assertEquals(RootMenuItem.MUSIC, page.selectedItem)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun menuPressOnRootMenuIncrementsSeqAndReEmitsCurrentItemAnnouncement() = runTest {
        val page = provider()
        page.speech.test {
            assertEquals(SpeechToken.ROOT_WEATHER_EMPTY, awaitItem().single().token)

            page.onInput(FunctionId.MENU)
            assertEquals(SpeechToken.ROOT_WEATHER_EMPTY, awaitItem().single().token)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stateChangeWhileCursorIsStationaryDoesNotEmitSpeech() = runTest {
        val musicState = MutableStateFlow(
            com.opensab.domain.music.MusicState(
                status = com.opensab.domain.music.PlaybackStatus.PAUSED,
                metadata = com.opensab.domain.music.TrackMetadata(title = "Hotel California", artist = "Eagles", album = "")
            )
        )
        val page = provider(musicState = musicState)
        page.speech.test {
            assertEquals(SpeechToken.ROOT_WEATHER_EMPTY, awaitItem().single().token)

            page.onInput(FunctionId.NEXT) // NAVIGATION
            awaitItem()
            page.onInput(FunctionId.NEXT) // CALL
            awaitItem()
            page.onInput(FunctionId.NEXT) // MESSAGE
            awaitItem()
            page.onInput(FunctionId.NEXT) // MUSIC
            assertEquals(SpeechToken.ROOT_MUSIC, awaitItem().single().token)

            // Now stationary on MUSIC, playback starts
            musicState.value = musicState.value.copy(status = com.opensab.domain.music.PlaybackStatus.PLAYING)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
