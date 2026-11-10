package com.opensab.cluster.weather

import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechQuantity
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.speech.SpeechWord
import com.opensab.domain.weather.WeatherConditionType
import com.opensab.domain.weather.WeatherForecastSegment
import com.opensab.domain.weather.WeatherInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "The weather in Vigo. Now, partly cloudy, 21 degrees. In 3 hours, rain, 18 degrees."
 *
 * This is the only place where what the rider hears can be pinned down without a phone, an engine
 * and a downloaded language, so it is pinned down here in full: the order, the arguments and the
 * two silences — no locality, no segments.
 */
class WeatherForecastScriptTest {

    private val hour = 3_600_000L

    private fun reading(
        locality: String = "Vigo",
        segments: List<WeatherForecastSegment> = emptyList()
    ) = WeatherInfo(
        locationName = locality,
        temperature = 21,
        temperatureUnit = "°C",
        condition = "Partly cloudy",
        type = WeatherConditionType.SUN_AND_CLOUD,
        hourly = segments,
        timestamp = 0L
    )

    private fun segment(atHour: Long, temperature: Int, type: WeatherConditionType) =
        WeatherForecastSegment(
            timestamp = atHour * hour,
            temperature = temperature,
            condition = "whatever the screen says",
            type = type
        )

    @Test
    fun theScriptOpensWithTheLocality() {
        val script = forecastScript(reading(), nowMillis = 0L)

        assertEquals(
            SpeechRequest(SpeechToken.WEATHER_FORECAST_MESSAGE, SpeechArg.Text("Vigo")),
            script.first()
        )
    }

    /**
     * The locality is best effort — the `Geocoder` fails and it stays empty —
     * and "the weather in" on its own is worse than not saying it.
     */
    @Test
    fun aReadingWithNoLocalityOpensWithTheCurrentLineInstead() {
        val script = forecastScript(reading(locality = ""), nowMillis = 0L)

        assertEquals(SpeechToken.WEATHER_CURRENT_LINE, script.first().token)
    }

    /** The reading is said with a line of its own, because the forecast line would say "in 0 hours". */
    @Test
    fun theCurrentLineSaysTheConditionAndTheTemperature() {
        val script = forecastScript(reading(), nowMillis = 0L)

        assertEquals(
            SpeechRequest(
                SpeechToken.WEATHER_CURRENT_LINE,
                SpeechArg.Word(SpeechWord.WEATHER_SUN_AND_CLOUD),
                SpeechArg.Quantity(SpeechQuantity.DEGREES, 21)
            ),
            script[1]
        )
    }

    @Test
    fun thereIsOneLinePerForecastSegment() {
        val script = forecastScript(
            reading(
                segments = listOf(
                    segment(3, 18, WeatherConditionType.RAIN),
                    segment(6, 16, WeatherConditionType.STORM),
                    segment(9, 14, WeatherConditionType.CLOUDY)
                )
            ),
            nowMillis = 0L
        )

        // Locality, the current reading, and then one line per slot.
        assertEquals(5, script.size)
        assertTrue(script.drop(2).all { it.token == SpeechToken.WEATHER_FORECAST_LINE })
    }

    @Test
    fun eachLineSaysHowManyHoursAwayItsSegmentIs() {
        val script = forecastScript(
            reading(
                segments = listOf(
                    segment(3, 18, WeatherConditionType.RAIN),
                    segment(6, 16, WeatherConditionType.STORM)
                )
            ),
            nowMillis = 0L
        )

        assertEquals(
            SpeechRequest(
                SpeechToken.WEATHER_FORECAST_LINE,
                SpeechArg.Quantity(SpeechQuantity.HOURS, 3),
                SpeechArg.Word(SpeechWord.WEATHER_RAIN),
                SpeechArg.Quantity(SpeechQuantity.DEGREES, 18)
            ),
            script[2]
        )
        assertEquals(
            SpeechArg.Quantity(SpeechQuantity.HOURS, 6),
            script[3].args.first()
        )
    }

    /**
     * The hour is counted from now and not from the fetch, so a cached reading restored an hour
     * later says one hour less. A slot two hours and fifty-eight minutes out is three hours out to
     * anybody wearing a helmet.
     */
    @Test
    fun theHourCountIsRoundedToWholeHours() {
        val script = forecastScript(
            reading(segments = listOf(segment(3, 18, WeatherConditionType.RAIN))),
            nowMillis = 2 * 60 * 1000L
        )

        assertEquals(SpeechArg.Quantity(SpeechQuantity.HOURS, 3), script[2].args.first())
    }

    /**
     * The whole point is that the page says something the carousel does not already show, but a
     * reading with no slots — an old cache, a partial answer — is still worth its two lines.
     */
    @Test
    fun aReadingWithNoSegmentsStillSaysTheLocalityAndTheCurrentWeather() {
        val script = forecastScript(reading(), nowMillis = 0L)

        assertEquals(2, script.size)
        assertEquals(SpeechToken.WEATHER_FORECAST_MESSAGE, script[0].token)
        assertEquals(SpeechToken.WEATHER_CURRENT_LINE, script[1].token)
    }

    /**
     * A reading restored from the cache can be old enough for its first slots to have gone by, and
     * a line about an hour that has already happened is worse than one line fewer.
     */
    @Test
    fun aSegmentThatHasAlreadyPassedIsNotSaid() {
        val script = forecastScript(
            reading(
                segments = listOf(
                    segment(3, 18, WeatherConditionType.RAIN),
                    segment(6, 16, WeatherConditionType.STORM)
                )
            ),
            nowMillis = 4 * hour
        )

        assertEquals(3, script.size)
        assertEquals(SpeechArg.Quantity(SpeechQuantity.HOURS, 2), script[2].args.first())
    }

    @Test
    fun aNullReadingSaysNothing() {
        assertEquals(emptyList<SpeechRequest>(), forecastScript(null, nowMillis = 0L))
    }

    /**
     * The arity is checked when a request is built, so this passes by not throwing — which is the
     * point: it sweeps every branch of the script instead of the three a happy path reaches.
     */
    @Test
    fun everyRequestOfTheScriptCarriesTheRightNumberOfArguments() {
        val script = forecastScript(
            reading(
                segments = listOf(
                    segment(3, 18, WeatherConditionType.RAIN),
                    segment(6, 16, WeatherConditionType.STORM),
                    segment(9, 14, WeatherConditionType.CLOUDY)
                )
            ),
            nowMillis = 0L
        )

        for (request in script) {
            assertEquals(
                "SpeechToken.${request.token} was built with the wrong number of arguments",
                request.token.arity,
                request.args.size
            )
        }
    }

    /**
     * Sweeping the catalogue is what the exhaustive `when` cannot do: it forces a branch per type,
     * but not a *different* word behind that branch, and a copied line would have the storm spoken
     * as fog.
     */
    @Test
    fun everyConditionTypeHasItsOwnWord() {
        val words = WeatherConditionType.entries.map { it.speechWord() }

        assertEquals(
            "Two condition types are spoken with the same word",
            words.size,
            words.toSet().size
        )
        assertEquals(SpeechWord.WEATHER_RAIN, WeatherConditionType.RAIN.speechWord())
        assertEquals(SpeechWord.WEATHER_UNKNOWN, WeatherConditionType.UNKNOWN.speechWord())
    }
}
