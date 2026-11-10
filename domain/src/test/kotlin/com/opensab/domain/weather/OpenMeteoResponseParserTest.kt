package com.opensab.domain.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenMeteoResponseParserTest {

    private fun body(temperature: String, code: String, unit: String = "°C"): String = """
        {
          "latitude": 40.42,
          "longitude": -3.7,
          "generationtime_ms": 0.0289,
          "utc_offset_seconds": 0,
          "timezone": "GMT",
          "timezone_abbreviation": "GMT",
          "elevation": 665.0,
          "current_units": {
            "time": "iso8601",
            "interval": "seconds",
            "temperature_2m": "$unit",
            "weather_code": "wmo code"
          },
          "current": {
            "time": "2026-09-01T10:00",
            "interval": 900,
            "temperature_2m": $temperature,
            "weather_code": $code
          }
        }
    """.trimIndent()

    @Test
    fun parsesACurrentWeatherResponseIntoAnObservation() {
        val observation = OpenMeteoResponseParser.parse(body(temperature = "21.6", code = "2"))

        assertEquals(
            WeatherObservation(
                temperature = 22,
                unitSymbol = "°C",
                condition = WmoCondition(WeatherConditionType.SUN_AND_CLOUD, WmoCaption.PARTLY_CLOUDY)
            ),
            observation
        )
    }

    /**
     * `WeatherInfo.temperature` is an Int because the cluster line has no room for decimals.
     */
    @Test
    fun roundsTheTemperatureToAWholeDegree() {
        assertEquals(22, OpenMeteoResponseParser.parse(body("21.6", "0"))?.temperature)
        assertEquals(21, OpenMeteoResponseParser.parse(body("21.4", "0"))?.temperature)
        assertEquals(0, OpenMeteoResponseParser.parse(body("-0.4", "0"))?.temperature)
        assertEquals(-3, OpenMeteoResponseParser.parse(body("-2.6", "0"))?.temperature)
        assertEquals(18, OpenMeteoResponseParser.parse(body("18", "0"))?.temperature)
    }

    /**
     * The response says which scale it answered in, so the page never has to assume the request
     * was honoured.
     */
    @Test
    fun readsTheUnitSymbolFromTheResponseInsteadOfAssumingIt() {
        assertEquals("°F", OpenMeteoResponseParser.parse(body("70.2", "0", unit = "°F"))?.unitSymbol)
    }

    @Test
    fun mapsTheWeatherCodeThroughTheWmoTable() {
        assertEquals(
            WmoCondition(WeatherConditionType.STORM, WmoCaption.THUNDERSTORM),
            OpenMeteoResponseParser.parse(body("14.0", "95"))?.condition
        )
        assertEquals(
            WmoCondition(WeatherConditionType.UNKNOWN, WmoCaption.UNKNOWN),
            OpenMeteoResponseParser.parse(body("14.0", "123"))?.condition
        )
    }

    @Test
    fun rejectsMalformedJsonInsteadOfThrowing() {
        assertNull(OpenMeteoResponseParser.parse(""))
        assertNull(OpenMeteoResponseParser.parse("not json at all"))
        assertNull(OpenMeteoResponseParser.parse("""{"current": """))
    }

    @Test
    fun rejectsAResponseWithoutAUsableCurrentBlock() {
        assertNull(OpenMeteoResponseParser.parse("""{"error": true, "reason": "Invalid latitude"}"""))
        assertNull(OpenMeteoResponseParser.parse("""{"current": {"time": "2026-09-01T10:00"}}"""))
        assertNull(
            OpenMeteoResponseParser.parse(
                """{"current": {"temperature_2m": 21.6}, "current_units": {"temperature_2m": "°C"}}"""
            )
        )
    }

    /**
     * A response that answers the reading but not the units is still usable: the scale defaults to
     * the degree sign alone rather than throwing the whole reading away.
     */
    @Test
    fun fallsBackToABareDegreeSignWhenTheUnitsBlockIsMissing() {
        val observation = OpenMeteoResponseParser.parse(
            """{"current": {"temperature_2m": 21.6, "weather_code": 0}}"""
        )

        assertEquals(22, observation?.temperature)
        assertEquals("°", observation?.unitSymbol)
    }

    // -------------------------------------------------------------------------------------------
    // The hourly block
    // -------------------------------------------------------------------------------------------

    /**
     * The shape the request asks for: `timeformat=unixtime`, so the three arrays are
     * epochs, degrees and WMO codes, index by index.
     */
    private fun bodyWithHourly(
        times: String = "[1756684800, 1756688400, 1756692000]",
        temperatures: String = "[18.2, 17.6, 16.4]",
        codes: String = "[0, 2, 61]"
    ): String = """
        {
          "current_units": { "temperature_2m": "°C", "weather_code": "wmo code" },
          "current": { "temperature_2m": 21.6, "weather_code": 2 },
          "hourly_units": { "time": "unixtime", "temperature_2m": "°C" },
          "hourly": {
            "time": $times,
            "temperature_2m": $temperatures,
            "weather_code": $codes
          }
        }
    """.trimIndent()

    @Test
    fun readsTheHourlyBlockIntoSamples() {
        val samples = OpenMeteoResponseParser.parseHourly(bodyWithHourly())

        assertEquals(3, samples.size)
        assertEquals(1756684800L, samples[0].epochSeconds)
        assertEquals(18, samples[0].temperature)
        assertEquals(1756692000L, samples[2].epochSeconds)
        assertEquals(16, samples[2].temperature)
    }

    /** The same table the current reading goes through, so a slot and a reading never disagree. */
    @Test
    fun hourlySamplesCarryTheirWmoCondition() {
        val samples = OpenMeteoResponseParser.parseHourly(bodyWithHourly())

        assertEquals(WmoCondition(WeatherConditionType.SUNNY, WmoCaption.CLEAR), samples[0].condition)
        assertEquals(WmoCondition(WeatherConditionType.SUN_AND_CLOUD, WmoCaption.PARTLY_CLOUDY), samples[1].condition)
        assertEquals(WmoCondition(WeatherConditionType.RAIN, WmoCaption.RAIN), samples[2].condition)
    }

    /**
     * A response with only `current`: a reading with no hours ahead is still a reading, and that
     * is what a phone with a cache written before `hourly` existed restores.
     */
    @Test
    fun aResponseWithNoHourlyBlockGivesNoSamples() {
        assertEquals(emptyList<HourlySample>(), OpenMeteoResponseParser.parseHourly(body("21.6", "2")))
    }

    @Test
    fun malformedJsonGivesNoSamplesInsteadOfThrowing() {
        assertEquals(emptyList<HourlySample>(), OpenMeteoResponseParser.parseHourly(""))
        assertEquals(emptyList<HourlySample>(), OpenMeteoResponseParser.parseHourly("not json at all"))
        assertEquals(emptyList<HourlySample>(), OpenMeteoResponseParser.parseHourly("""{"hourly": """))
    }

    /**
     * Three arrays read index by index is the one way this parser can throw, and it must not: a
     * short array is a truncated forecast, not a lost reading.
     */
    @Test
    fun mismatchedHourlyArraysAreTruncatedToTheShortest() {
        val samples = OpenMeteoResponseParser.parseHourly(
            bodyWithHourly(
                times = "[1756684800, 1756688400, 1756692000]",
                temperatures = "[18.2, 17.6]",
                codes = "[0, 2, 61]"
            )
        )

        assertEquals(2, samples.size)
        assertEquals(1756688400L, samples[1].epochSeconds)
    }

    /**
     * Open-Meteo answers `null` for a slot it has no value for. One hole is one slot lost, not the
     * whole forecast, and the slots around it keep their own timestamps.
     */
    @Test
    fun aSlotWithANullTemperatureIsSkippedAndTheRestSurvive() {
        val samples = OpenMeteoResponseParser.parseHourly(
            bodyWithHourly(temperatures = "[18.2, null, 16.4]")
        )

        assertEquals(2, samples.size)
        assertEquals(1756684800L, samples[0].epochSeconds)
        assertEquals(1756692000L, samples[1].epochSeconds)
        assertEquals(16, samples[1].temperature)
    }

    /** Two entries into the same body, and neither may spoil the other. */
    @Test
    fun theCurrentBlockStillParsesWhenTheHourlyBlockIsPresent() {
        val observation = OpenMeteoResponseParser.parse(bodyWithHourly())

        assertEquals(22, observation?.temperature)
        assertEquals(
            WmoCondition(WeatherConditionType.SUN_AND_CLOUD, WmoCaption.PARTLY_CLOUDY),
            observation?.condition
        )
    }
}
