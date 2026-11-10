package com.opensab.app.e2e

import com.opensab.domain.weather.WeatherConditionType
import com.opensab.domain.weather.WeatherForecastSegment
import com.opensab.domain.weather.WeatherInfo
import com.opensab.domain.weather.WmoCaption
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.BodyType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The first row of this project that is heard rather than seen, driven over the wire.
 *
 * Nothing here calls into the engine: the press arrives as a `FunctionNotification` on the
 * simulated BTU, is decoded by the real `DefaultInputDispatcher`, opens the page through the graph
 * the Hilt factories build, and what comes out the far end is an utterance. The platform half of
 * the voice is the one thing no test here can reach.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeatherSpeechE2eTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    /** Vigo, partly cloudy, and rain in three hours: enough for a header and two lines. */
    private fun forecast() = WeatherInfo(
        locationName = "Vigo",
        temperature = 21,
        temperatureUnit = "°C",
        caption = WmoCaption.PARTLY_CLOUDY,
        type = WeatherConditionType.SUN_AND_CLOUD,
        hourly = listOf(
            WeatherForecastSegment(
                timestamp = System.currentTimeMillis() + 3 * 3_600_000L,
                temperature = 18,
                caption = WmoCaption.RAIN,
                type = WeatherConditionType.RAIN
            )
        )
    )

    @Test
    fun `entering weather from the carousel says the forecast out loud`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.weather.updateWeather(forecast())
        harness.connect().getOrThrow()
        harness.handOverScreen()

        // The cursor starts on Weather, so one press is all it takes.
        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        assertEquals(
            "SELECT on Weather must open a page, and a page is a LIVE_SCREEN body",
            BodyType.LIVE_SCREEN.value,
            harness.btu.baseFrames().last().bodyType
        )
        assertEquals(
            listOf(
                "ROOT_WEATHER[WEATHER_SUN_AND_CLOUD, 21 DEGREES]",
                "WEATHER_FORECAST_MESSAGE[Vigo]",
                "WEATHER_CURRENT_LINE[WEATHER_SUN_AND_CLOUD, 21 DEGREES]",
                "WEATHER_FORECAST_LINE[3 HOURS, WEATHER_RAIN, 18 DEGREES]"
            ),
            harness.spoken.utterances.map { it.text }
        )
    }

    /**
     * BACK is the cancel button on this page, and cutting an utterance is a normal outcome: the
     * voice stops, the carousel comes back, and nothing throws.
     */
    @Test
    fun `back on the weather page cuts the forecast`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.spoken.speaksUntilStopped = true
        harness.weather.updateWeather(forecast())
        harness.connect().getOrThrow()
        harness.handOverScreen()
        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        harness.btu.press(FunctionId.BACK)
        runCurrent()

        assertEquals(
            listOf(
                "ROOT_WEATHER[WEATHER_SUN_AND_CLOUD, 21 DEGREES]",
                "WEATHER_FORECAST_MESSAGE[Vigo]",
                "ROOT_WEATHER[WEATHER_SUN_AND_CLOUD, 21 DEGREES]"
            ),
            harness.spoken.utterances.map { it.text }
        )
        assertEquals(2, harness.spoken.stopCalls)
        assertEquals(
            "BACK returns the carousel, which is a MENU body",
            BodyType.MENU.value,
            harness.btu.baseFrames().last().bodyType
        )
    }

    /**
     * A cache written by an older build restores a reading with no slots. The entry still opens,
     * the page still mounts, and it says the two lines it has.
     */
    @Test
    fun `a reading with no segments still opens the entry and says what it has`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.weather.updateWeather(forecast().copy(hourly = emptyList()))
        harness.connect().getOrThrow()
        harness.handOverScreen()

        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        assertEquals(
            BodyType.LIVE_SCREEN.value,
            harness.btu.baseFrames().last().bodyType
        )
        assertEquals(
            listOf(
                "ROOT_WEATHER[WEATHER_SUN_AND_CLOUD, 21 DEGREES]",
                "WEATHER_FORECAST_MESSAGE[Vigo]",
                "WEATHER_CURRENT_LINE[WEATHER_SUN_AND_CLOUD, 21 DEGREES]"
            ),
            harness.spoken.utterances.map { it.text }
        )
    }

    /**
     * And with no reading at all the page opens on its standby frame, where SELECT asks for one,
     * and there is nothing to say.
     */
    @Test
    fun `with no reading the page opens on standby and nothing is said`() = testScope.runTest {
        val harness = SessionHarness(backgroundScope)
        harness.connect().getOrThrow()
        harness.handOverScreen()

        harness.btu.press(FunctionId.SELECT)
        runCurrent()

        assertEquals(
            "Without a forecast the press still opens the weather page",
            BodyType.LIVE_SCREEN.value,
            harness.btu.baseFrames().last().bodyType
        )
        assertEquals(
            listOf("ROOT_WEATHER_EMPTY[]"),
            harness.spoken.utterances.map { it.text }
        )
    }
}
