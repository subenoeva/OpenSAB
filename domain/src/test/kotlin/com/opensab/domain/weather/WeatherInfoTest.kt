package com.opensab.domain.weather

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading is the one thing of this feature that outlives the process, so its shape is a
 * compatibility contract and not an implementation detail: there are phones with a
 * `weather_last_reading` on disk written by older versions of this class.
 */
class WeatherInfoTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Exactly what the first version wrote to DataStore: no `hourly`, and a `forecastSummary` that
     * no longer exists. It has to load, and it has to load as a **reading** and not as a hole — the
     * carousel entry hangs on the reading being there, and closing it on every phone that already
     * had a cache would blink the page to standby.
     */
    @Test
    fun aReadingStoredBeforeForecastSegmentsExistedStillLoads() {
        val storedByPlan32 = """
            {
              "locationName": "Barcelona",
              "temperature": 24,
              "temperatureUnit": "°C",
              "condition": "Clear",
              "type": "SUNNY",
              "forecastSummary": "",
              "timestamp": 1000000
            }
        """.trimIndent()

        val restored = json.decodeFromString<WeatherInfo>(storedByPlan32)

        assertEquals("Barcelona", restored.locationName)
        assertEquals(24, restored.temperature)
        assertEquals(1_000_000L, restored.timestamp)
        assertEquals(emptyList<WeatherForecastSegment>(), restored.hourly)
    }

    /**
     * The entry that was actually on the test phone, copied byte for byte with
     * `adb shell run-as com.opensab cat files/datastore/vehicles.preferences_pb`.
     *
     * It is **shorter than the fixture above**, and that is the point: `kotlinx.serialization` does
     * not write a field holding its default value, so what that phone has on disk carries
     * neither `hourly` nor `temperatureUnit` nor `type` nor the `forecastSummary` that the
     * hand-written case still spells out. The real artifact exercises the rule harder than the
     * invented one, and it is the one that decides whether the carousel entry opens on the first
     * frame of the next session.
     */
    @Test
    fun theReadingCachedOnTheTestPhoneDecodes() {
        val fromTheDevice =
            """{"locationName":"Porto do Exemplo","temperature":21,"condition":"Clear","timestamp":1788291012328}"""

        val restored = json.decodeFromString<WeatherInfo>(fromTheDevice)

        assertEquals("Porto do Exemplo", restored.locationName)
        assertEquals(21, restored.temperature)
        assertEquals("Clear", restored.condition)
        assertEquals(1788291012328L, restored.timestamp)
        assertEquals("°C", restored.temperatureUnit)
        assertEquals(WeatherConditionType.SUNNY, restored.type)
        assertEquals(emptyList<WeatherForecastSegment>(), restored.hourly)
    }

    /**
     * The other half of the same rule, and the reason the one above is short: what this app writes
     * is as lean as what it reads, so today's reading with no slots is stored exactly like the
     * first version's. A future field with a default costs nothing on disk and breaks nothing already
     * there.
     */
    @Test
    fun aFieldHoldingItsDefaultIsNotWrittenToDisk() {
        val reading = WeatherInfo(
            locationName = "Vigo",
            temperature = 14,
            condition = "Rain",
            type = WeatherConditionType.RAIN,
            timestamp = 1_000_000L
        )

        val encoded = json.encodeToString(reading)

        assertFalse("An empty forecast must not be written out", encoded.contains("hourly"))
        assertTrue(encoded.contains("\"condition\":\"Rain\""))
    }

    /** And the same thing said as the rule it is, rather than as a property of one JSON blob. */
    @Test
    fun aReadingWithoutSegmentsIsStillAReading() {
        val reading = WeatherInfo(
            locationName = "Vigo",
            temperature = 14,
            condition = "Rain",
            type = WeatherConditionType.RAIN
        )

        assertTrue(reading.hourly.isEmpty())
    }

    @Test
    fun segmentsSurviveARoundTripThroughJson() {
        val reading = WeatherInfo(
            locationName = "Vigo",
            temperature = 14,
            condition = "Rain",
            type = WeatherConditionType.RAIN,
            hourly = listOf(
                WeatherForecastSegment(
                    timestamp = 1_010_000L,
                    temperature = 13,
                    condition = "Overcast",
                    type = WeatherConditionType.CLOUDY
                )
            ),
            timestamp = 1_000_000L
        )

        val restored = json.decodeFromString<WeatherInfo>(json.encodeToString(reading))

        assertEquals(reading, restored)
    }

    /**
     * What an older version wrote to DataStore: an English caption in `condition` and no `caption`
     * at all. The same compatibility contract, one shape later.
     *
     * `caption` comes back UNKNOWN and that is the accepted cost: `DefaultWeatherSource
     * .start()` fires its first `fetch(force = true)` with the session and not on the fifteen-minute
     * tick, so the unknown caption lives as long as one HTTP round trip. The temperature, the
     * location and the icon are right the whole time, because `type` is persisted too.
     */
    @Test
    fun aReadingWrittenBeforeTheCaptionExistedStillLoads() {
        val storedByPlan35 = """
            {
              "locationName": "Porto do Exemplo",
              "temperature": 21,
              "temperatureUnit": "°C",
              "condition": "Partly cloudy",
              "type": "SUN_AND_CLOUD",
              "timestamp": 1788291012328
            }
        """.trimIndent()

        val restored = json.decodeFromString<WeatherInfo>(storedByPlan35)

        assertEquals("Porto do Exemplo", restored.locationName)
        assertEquals(21, restored.temperature)
        assertEquals(WeatherConditionType.SUN_AND_CLOUD, restored.type)
        assertEquals("Partly cloudy", restored.condition)
        assertEquals(WmoCaption.UNKNOWN, restored.caption)
    }

    /** The same rule one level down: a stored segment is as old as the reading that carries it. */
    @Test
    fun aSegmentWrittenBeforeTheCaptionExistedStillLoads() {
        val storedByPlan35 = """
            {
              "locationName": "Vigo",
              "temperature": 14,
              "condition": "Rain",
              "type": "RAIN",
              "hourly": [
                {"timestamp": 1010000, "temperature": 13, "condition": "Overcast", "type": "CLOUDY"}
              ],
              "timestamp": 1000000
            }
        """.trimIndent()

        val restored = json.decodeFromString<WeatherInfo>(storedByPlan35)

        assertEquals(1, restored.hourly.size)
        assertEquals("Overcast", restored.hourly.first().condition)
        assertEquals(WmoCaption.UNKNOWN, restored.hourly.first().caption)
        assertEquals(WeatherConditionType.CLOUDY, restored.hourly.first().type)
    }

    @Test
    fun theCaptionSurvivesARoundTrip() {
        val reading = WeatherInfo(
            locationName = "Vigo",
            temperature = 14,
            caption = WmoCaption.RAIN,
            type = WeatherConditionType.RAIN,
            hourly = listOf(
                WeatherForecastSegment(
                    timestamp = 1_010_000L,
                    temperature = 13,
                    caption = WmoCaption.OVERCAST,
                    type = WeatherConditionType.CLOUDY
                )
            ),
            timestamp = 1_000_000L
        )

        assertEquals(reading, json.decodeFromString<WeatherInfo>(json.encodeToString(reading)))
    }

    /**
     * The compatibility field is read and never written. `kotlinx.serialization` leaves out a field
     * holding its default, so a reading stored from here on carries no English at all: what is on
     * disk stops being a sentence in one language.
     */
    @Test
    fun aReadingWrittenTodayCarriesNoEnglishCondition() {
        val reading = WeatherInfo(
            locationName = "Vigo",
            temperature = 14,
            caption = WmoCaption.RAIN,
            type = WeatherConditionType.RAIN,
            timestamp = 1_000_000L
        )

        val encoded = json.encodeToString(reading)

        assertFalse("the English caption must not be written any more", encoded.contains("condition"))
        assertTrue(encoded.contains("\"caption\":\"RAIN\""))
    }
}
