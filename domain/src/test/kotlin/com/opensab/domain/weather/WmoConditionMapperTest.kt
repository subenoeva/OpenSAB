package com.opensab.domain.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WmoConditionMapperTest {

    private val expected = mapOf(
        0 to WmoCondition(WeatherConditionType.SUNNY, WmoCaption.CLEAR),
        1 to WmoCondition(WeatherConditionType.SUN_AND_CLOUD, WmoCaption.MAINLY_CLEAR),
        2 to WmoCondition(WeatherConditionType.SUN_AND_CLOUD, WmoCaption.PARTLY_CLOUDY),
        3 to WmoCondition(WeatherConditionType.CLOUDY, WmoCaption.OVERCAST),
        45 to WmoCondition(WeatherConditionType.FOG, WmoCaption.FOG),
        48 to WmoCondition(WeatherConditionType.FOG, WmoCaption.RIME_FOG),
        51 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.DRIZZLE),
        53 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.DRIZZLE),
        55 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.DRIZZLE),
        56 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.FREEZING_DRIZZLE),
        57 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.FREEZING_DRIZZLE),
        61 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.RAIN),
        63 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.RAIN),
        65 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.RAIN),
        66 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.FREEZING_RAIN),
        67 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.FREEZING_RAIN),
        71 to WmoCondition(WeatherConditionType.SNOW, WmoCaption.SNOW),
        73 to WmoCondition(WeatherConditionType.SNOW, WmoCaption.SNOW),
        75 to WmoCondition(WeatherConditionType.SNOW, WmoCaption.SNOW),
        77 to WmoCondition(WeatherConditionType.SNOW, WmoCaption.SNOW_GRAINS),
        80 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.RAIN_SHOWERS),
        81 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.RAIN_SHOWERS),
        82 to WmoCondition(WeatherConditionType.RAIN, WmoCaption.RAIN_SHOWERS),
        85 to WmoCondition(WeatherConditionType.SNOW, WmoCaption.SNOW_SHOWERS),
        86 to WmoCondition(WeatherConditionType.SNOW, WmoCaption.SNOW_SHOWERS),
        95 to WmoCondition(WeatherConditionType.STORM, WmoCaption.THUNDERSTORM),
        96 to WmoCondition(WeatherConditionType.STORM, WmoCaption.THUNDERSTORM_WITH_HAIL),
        99 to WmoCondition(WeatherConditionType.STORM, WmoCaption.THUNDERSTORM_WITH_HAIL)
    )

    @Test
    fun mapsEveryDocumentedWmoCodeToItsConditionType() {
        for ((code, condition) in expected) {
            assertEquals("WMO code $code", condition, WmoConditionMapper.map(code))
        }
    }

    @Test
    fun mapsUnknownCodesToUnknownWithoutThrowing() {
        for (code in listOf(-1, 4, 30, 100, 999, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(
                "WMO code $code has no meaning and must degrade, not guess",
                WmoCondition(WeatherConditionType.UNKNOWN, WmoCaption.UNKNOWN),
                WmoConditionMapper.map(code)
            )
        }
    }

    /**
     * CLEAR is the night-time clear sky. Day and night are not modelled, so clear sky is always
     * SUNNY and CLEAR is unreachable from the real provider.
     */
    @Test
    fun clearSkyIsSunnyBecauseThereIsNoDayNightFlag() {
        assertEquals(WeatherConditionType.SUNNY, WmoConditionMapper.map(0).type)

        val reachable = (-5..120).map { WmoConditionMapper.map(it).type }.toSet()
        assertTrue(
            "CLEAR needs a day/night flag the provider does not send: $reachable",
            WeatherConditionType.CLEAR !in reachable
        )
    }

    /**
     * The seventeen captions and the codes are one table read from two sides: an entry nothing maps
     * to is a string somebody translated twice and nobody ever paints. The ASCII fold of the
     * captions is checked over both languages in `ClusterPhrasesTest`.
     */
    @Test
    fun everyCaptionIsReachableFromSomeCode() {
        val reached = (-5..120).map { WmoConditionMapper.map(it).caption }.toSet()

        assertEquals(emptySet<WmoCaption>(), WmoCaption.entries.toSet() - reached)
    }
}
