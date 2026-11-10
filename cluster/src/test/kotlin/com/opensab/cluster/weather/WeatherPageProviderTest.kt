package com.opensab.cluster.weather

import com.opensab.cluster.NavigationAction
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.icons.iconId
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.WeatherCaptionResolver
import com.opensab.cluster.text.englishClusterText
import com.opensab.cluster.text.englishWeatherCaptions
import com.opensab.domain.music.VolumeLevel
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.weather.MockWeatherSource
import com.opensab.domain.weather.WeatherConditionType
import com.opensab.domain.weather.WeatherForecastSegment
import com.opensab.domain.weather.WeatherInfo
import com.opensab.domain.weather.WmoCaption
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherPageProviderTest {

    private class TestVolumeSource : VolumeSource {
        val state = MutableStateFlow(VolumeLevel())
        override val level = state

        var raiseCalls = 0
        var lowerCalls = 0
        var muteCalls = 0

        override fun start() {}
        override fun stop() {}
        override fun raise() { raiseCalls++ }
        override fun lower() { lowerCalls++ }
        override fun toggleMute() { muteCalls++ }
    }

    @Test
    fun `renders active weather info with temperature, condition, and location`() {
        val weatherSource = MockWeatherSource()
        val volumeSource = TestVolumeSource()
        val provider = WeatherPageProvider(weatherSource.currentWeather, englishClusterText, englishWeatherCaptions, weatherSource, volumeSource)

        weatherSource.updateWeather(
            WeatherInfo(
                locationName = "Barcelona",
                temperature = 25,
                temperatureUnit = "°C",
                caption = WmoCaption.CLEAR,
                type = WeatherConditionType.SUNNY
            )
        )

        val frame = provider.render(requestId = 301) as ProjectionFrame.LiveScreen
        assertEquals(301, frame.requestId)
        assertEquals("Weather", frame.headerText)
        assertEquals(ColorId.GREY, frame.headerBgColorId)
        assertEquals("25°C Clear", frame.contentTextLine1)
        assertEquals("Barcelona", frame.contentTextLine2)
        // The location line takes the place of the icon: a live screen carries one or the other.
        assertEquals(IconId.IC_NONE, frame.contentIconId)
        assertEquals(IconId.LIVE_CANCEL_TWO_BUTTONS, frame.leftActionContentIconId)
    }

    @Test
    fun `renders standby screen when no weather data is available`() {
        val weatherSource = MockWeatherSource()
        val provider = WeatherPageProvider(weatherSource.currentWeather, englishClusterText, englishWeatherCaptions, weatherSource)

        val frame = provider.render(requestId = 302) as ProjectionFrame.LiveScreen
        assertEquals(302, frame.requestId)
        assertEquals("Weather", frame.headerText)
        assertEquals(ColorId.GREY, frame.headerBgColorId)
        assertEquals(IconId.NO_WEATHER, frame.contentIconId)
        assertEquals("No Data", frame.contentTextLine1)
        assertEquals("", frame.contentTextLine2)
    }

    @Test
    fun `unknown weather condition maps to WEATHER_CLEAR icon`() {
        assertEquals(IconId.WEATHER_CLEAR, WeatherConditionType.UNKNOWN.iconId)
    }

    @Test
    fun `back button pops back to root menu`() {
        val weatherSource = MockWeatherSource()
        val provider = WeatherPageProvider(weatherSource.currentWeather, englishClusterText, englishWeatherCaptions, weatherSource)

        val action = provider.onInput(FunctionId.BACK)
        assertEquals(NavigationAction.Pop, action)
    }

    @Test
    fun `menu button resets back to root menu`() {
        val weatherSource = MockWeatherSource()
        val provider = WeatherPageProvider(weatherSource.currentWeather, englishClusterText, englishWeatherCaptions, weatherSource)

        val action = provider.onInput(FunctionId.MENU)
        assertEquals(NavigationAction.Reset, action)
    }

    @Test
    fun `select button requests weather refresh`() {
        val weatherSource = MockWeatherSource()
        val provider = WeatherPageProvider(weatherSource.currentWeather, englishClusterText, englishWeatherCaptions, weatherSource)

        val action = provider.onInput(FunctionId.SELECT)
        assertEquals(NavigationAction.None, action)
        assertEquals(1, weatherSource.refreshCalls)
    }

    // -------------------------------------------------------------------------------------------
    // The page exists to speak
    // -------------------------------------------------------------------------------------------

    private fun reading(locality: String = "Vigo") = WeatherInfo(
        locationName = locality,
        temperature = 21,
        temperatureUnit = "°C",
        caption = WmoCaption.PARTLY_CLOUDY,
        type = WeatherConditionType.SUN_AND_CLOUD,
        hourly = listOf(
            WeatherForecastSegment(
                timestamp = 3 * 3_600_000L,
                temperature = 18,
                caption = WmoCaption.RAIN,
                type = WeatherConditionType.RAIN
            )
        ),
        timestamp = 0L
    )

    private fun speakingPage(
        weatherSource: MockWeatherSource,
        volumeSource: VolumeSource? = null
    ) = WeatherPageProvider(
        currentWeather = weatherSource.currentWeather,
        copy = englishClusterText,
        captions = englishWeatherCaptions,
        weatherSource = weatherSource,
        volumeSource = volumeSource,
        nowMillis = { 0L }
    )

    /**
     * **Building this page must cost nothing.**
     *
     * A constructor that reads the clock and builds a whole script is a side effect where an
     * allocation was expected, and the page that speaks is the one where it would hurt most: the
     * script it built would be dated at construction time rather than at the moment the rider
     * hears it.
     *
     * The clock is the observable proxy: nobody touches it until somebody listens. This is the
     * particular case of the rule the whole catalog answers to in
     * `PageProviderConstructionContractTest`.
     */
    @Test
    fun `building the page reads no clock and offers nothing`() = runTest {
        var clockReads = 0
        val weatherSource = MockWeatherSource()
        weatherSource.updateWeather(reading())

        repeat(50) {
            WeatherPageProvider(
                currentWeather = weatherSource.currentWeather,
                copy = englishClusterText,
                captions = englishWeatherCaptions,
                weatherSource = weatherSource,
                nowMillis = { clockReads++; 0L }
            )
        }

        assertEquals("Fifty pages built for a boolean must cost fifty times nothing", 0, clockReads)
    }

    /**
     * And the script is decided at the moment somebody listens, which is when the engine mounts the
     * page. That instant is also the right one for the clock: the hours a line announces are
     * counted from when the rider hears it.
     */
    @Test
    fun `the script is decided when the page is first listened to`() = runTest {
        var clockReads = 0
        val weatherSource = MockWeatherSource()
        weatherSource.updateWeather(reading())
        val provider = WeatherPageProvider(
            currentWeather = weatherSource.currentWeather,
            copy = englishClusterText,
            captions = englishWeatherCaptions,
            weatherSource = weatherSource,
            nowMillis = { clockReads++; 0L }
        )

        provider.speech.first()

        assertEquals(1, clockReads)
    }

    /**
     * Listening twice is what the engine does when an interruption covers the page and then goes
     * away, and the second time there is nothing to say: the forecast must not start over.
     */
    @Test
    fun `listening a second time offers nothing`() = runTest {
        val weatherSource = MockWeatherSource()
        weatherSource.updateWeather(reading())
        val provider = speakingPage(weatherSource)

        provider.speech.first()

        assertNull(withTimeoutOrNull(1.seconds) { provider.speech.first() })
    }

    /**
     * The page is built by SELECT on the carousel, one instance per entry, so mounting it is the
     * moment it says what it has. Nothing here says it: that is the engine's job, and the whole
     * point of naming the phrases is that this can be checked with no phone in the room.
     */
    @Test
    fun `the script is offered as soon as the page is built`() = runTest {
        val weatherSource = MockWeatherSource()
        weatherSource.updateWeather(reading())

        val script = speakingPage(weatherSource).speech.first()

        assertEquals(
            listOf(
                SpeechToken.WEATHER_FORECAST_MESSAGE,
                SpeechToken.WEATHER_CURRENT_LINE,
                SpeechToken.WEATHER_FORECAST_LINE
            ),
            script.map { it.token }
        )
    }

    /** Without a reading there is nothing to say, and a page that says nothing offers nothing. */
    @Test
    fun `a page built with no reading offers no script`() = runTest {
        val provider = speakingPage(MockWeatherSource())

        assertNull(withTimeoutOrNull(1.seconds) { provider.speech.first() })
    }

    /**
     * SELECT stops meaning "update" and starts meaning "say it again", which is the only thing a
     * rider wants from the single key of a page that exists to speak.
     */
    @Test
    fun `select offers the script again`() = runTest {
        val weatherSource = MockWeatherSource()
        weatherSource.updateWeather(reading())
        val provider = speakingPage(weatherSource)

        provider.speech.first()
        provider.onInput(FunctionId.SELECT)

        assertEquals(
            SpeechToken.WEATHER_FORECAST_MESSAGE,
            provider.speech.first().first().token
        )
    }

    /**
     * And it still asks the source for a fresh reading, because a repeat should say the freshest
     * thing known — the request is debounced upstream, on the last attempt. It is
     * also what keeps invariant 1 of the mirror contract alive without teaching that harness what
     * a voice is.
     */
    @Test
    fun `select also asks the source for a fresh reading`() = runTest {
        val weatherSource = MockWeatherSource()
        weatherSource.updateWeather(reading())
        val provider = speakingPage(weatherSource)

        provider.onInput(FunctionId.SELECT)

        assertEquals(1, weatherSource.refreshCalls)
    }

    /**
     * The voice does not hang off the data flow. A refresh landing fifteen minutes in redraws the
     * frame, and interrupting the rider mid-sentence to start the forecast over is exactly what it
     * must not do.
     */
    @Test
    fun `a new reading redraws the page and offers no script`() = runTest {
        val weatherSource = MockWeatherSource()
        weatherSource.updateWeather(reading())
        val provider = speakingPage(weatherSource)
        provider.speech.first()

        weatherSource.updateWeather(reading(locality = "Santiago"))

        assertNull(withTimeoutOrNull(1.seconds) { provider.speech.first() })
        assertEquals("Santiago", (provider.render(1) as ProjectionFrame.LiveScreen).contentTextLine2)
    }

    @Test
    fun `previous and volume up buttons raise volume`() {
        val weatherSource = MockWeatherSource()
        val volumeSource = TestVolumeSource()
        val provider = WeatherPageProvider(weatherSource.currentWeather, englishClusterText, englishWeatherCaptions, weatherSource, volumeSource)

        provider.onInput(FunctionId.PREVIOUS)
        assertEquals(1, volumeSource.raiseCalls)

        provider.onInput(FunctionId.VOLUME_UP)
        assertEquals(2, volumeSource.raiseCalls)
    }

    @Test
    fun `next and volume down buttons lower volume`() {
        val weatherSource = MockWeatherSource()
        val volumeSource = TestVolumeSource()
        val provider = WeatherPageProvider(weatherSource.currentWeather, englishClusterText, englishWeatherCaptions, weatherSource, volumeSource)

        provider.onInput(FunctionId.NEXT)
        assertEquals(1, volumeSource.lowerCalls)

        provider.onInput(FunctionId.VOLUME_DOWN)
        assertEquals(2, volumeSource.lowerCalls)
    }

    @Test
    fun `volume mute button toggles mute`() {
        val weatherSource = MockWeatherSource()
        val volumeSource = TestVolumeSource()
        val provider = WeatherPageProvider(weatherSource.currentWeather, englishClusterText, englishWeatherCaptions, weatherSource, volumeSource)

        provider.onInput(FunctionId.VOLUME_MUTE)
        assertEquals(1, volumeSource.muteCalls)
    }

    @Test
    fun `retains state on resume`() {
        val weatherSource = MockWeatherSource()
        val provider = WeatherPageProvider(weatherSource.currentWeather, englishClusterText, englishWeatherCaptions, weatherSource)

        assertEquals(ResumeBehavior.RETAIN, provider.onResumeBehavior)
        assertEquals("WeatherPage", provider.name)
    }

    /**
     * Six zones, one per branch of [onInput] that does something. `VOLUME_MUTE` sits on `RIGHT` because `LEFT` already carries `BACK`.
     */
    private val expectedMirrorActions = listOf(
        MirrorAction(MirrorActionToken.REFRESH_WEATHER, FunctionId.SELECT, MirrorSlot.PRIMARY),
        MirrorAction(MirrorActionToken.GO_BACK, FunctionId.BACK, MirrorSlot.LEFT),
        MirrorAction(
            token = MirrorActionToken.VOLUME_UP,
            functionId = FunctionId.PREVIOUS,
            slot = MirrorSlot.UP,
            aliases = setOf(FunctionId.VOLUME_UP)
        ),
        MirrorAction(
            token = MirrorActionToken.VOLUME_DOWN,
            functionId = FunctionId.NEXT,
            slot = MirrorSlot.DOWN,
            aliases = setOf(FunctionId.VOLUME_DOWN)
        ),
        MirrorAction(MirrorActionToken.VOLUME_MUTE, FunctionId.VOLUME_MUTE, MirrorSlot.RIGHT),
        MirrorAction(MirrorActionToken.OPEN_ROOT_MENU, FunctionId.MENU, MirrorSlot.HEADER)
    )

    @Test
    fun `mirror declares refresh, back, the volume block, and the way out with a report on screen`() {
        val weatherSource = MockWeatherSource()
        val volumeSource = TestVolumeSource()
        val provider = WeatherPageProvider(weatherSource.currentWeather, englishClusterText, englishWeatherCaptions, weatherSource, volumeSource)

        weatherSource.updateWeather(
            WeatherInfo(
                locationName = "Barcelona",
                temperature = 25,
                temperatureUnit = "°C",
                caption = WmoCaption.CLEAR,
                type = WeatherConditionType.SUNNY
            )
        )

        assertEquals(expectedMirrorActions, provider.mirror().actions)
    }

    @Test
    fun `mirror declares the same actions on standby because no branch of onInput reads the report`() {
        val weatherSource = MockWeatherSource()
        val volumeSource = TestVolumeSource()
        val provider = WeatherPageProvider(weatherSource.currentWeather, englishClusterText, englishWeatherCaptions, weatherSource, volumeSource)

        assertEquals(expectedMirrorActions, provider.mirror().actions)
    }

    @Test
    fun `mirror twins the projected frame instead of wording the page again`() {
        val weatherSource = MockWeatherSource()
        val provider = WeatherPageProvider(weatherSource.currentWeather, englishClusterText, englishWeatherCaptions, weatherSource)

        weatherSource.updateWeather(
            WeatherInfo(
                locationName = "Barcelona",
                temperature = 25,
                temperatureUnit = "°C",
                caption = WmoCaption.CLEAR,
                type = WeatherConditionType.SUNNY
            )
        )

        val mirror = provider.mirror()
        assertEquals("WeatherPage", mirror.pageName)
        assertEquals("Weather", mirror.title)
        assertEquals(listOf("25°C Clear", "Barcelona"), mirror.lines)
        assertEquals(ColorId.GREY, mirror.accent)
    }

    // -------------------------------------------------------------------------------------------
    // Copy
    // -------------------------------------------------------------------------------------------

    /**
     * The caption goes through its own port, and it shares the line with the
     * temperature: what the cluster gets is one field of thirty bytes with both things in it.
     */
    @Test
    fun `should take the condition from the caption resolver`() {
        val page = WeatherPageProvider(
            currentWeather = MutableStateFlow(
                WeatherInfo(
                    locationName = "",
                    temperature = 20,
                    caption = WmoCaption.RAIN_SHOWERS,
                    type = WeatherConditionType.RAIN
                )
            ),
            copy = { "[${it.name}]" },
            captions = { "Chubascos" }
        )

        val frame = page.render(1) as ProjectionFrame.LiveScreen

        assertEquals("20°C Chubascos", frame.contentTextLine1)
        assertEquals("[HEADER_WEATHER]", frame.headerText)
    }

    /**
     * The standby screen says two things and words neither of them itself.
     */
    @Test
    fun `should take the standby lines from the resolver`() {
        val page = WeatherPageProvider(
            currentWeather = MutableStateFlow(null),
            copy = { "[${it.name}]" },
            captions = { "no reading, no caption" }
        )

        val frame = page.render(1) as ProjectionFrame.LiveScreen

        assertEquals("[HEADER_WEATHER]", frame.headerText)
        assertEquals("[EMPTY_DATA]", frame.contentTextLine1)
    }

    /**
     * An older cached reading still carries the English sentence in `condition`, and it is read and
     * never painted: the line says the caption, resolved through the port. That field exists so an
     * old blob still decodes, not so a phone that upgraded keeps showing English.
     */
    @Test
    fun `should paint the caption and not the English a cached reading still carries`() {
        val page = WeatherPageProvider(
            currentWeather = MutableStateFlow(
                WeatherInfo(
                    locationName = "Vigo",
                    temperature = 14,
                    condition = "Sunny",
                    caption = WmoCaption.RAIN,
                    type = WeatherConditionType.RAIN
                )
            ),
            copy = { "[${it.name}]" },
            captions = { "[${it.name}]" }
        )

        val frame = page.render(1) as ProjectionFrame.LiveScreen

        assertEquals("14°C [RAIN]", frame.contentTextLine1)
    }
}
