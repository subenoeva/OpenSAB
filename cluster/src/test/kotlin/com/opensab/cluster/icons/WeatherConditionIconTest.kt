package com.opensab.cluster.icons

import com.opensab.domain.weather.WeatherConditionType
import com.opensab.sab.protocol.projection.IconId
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherConditionIconTest {

    @Test
    fun `every condition keeps the icon it has today`() {
        val expected = mapOf(
            WeatherConditionType.SUNNY to IconId.WEATHER_SUNNY,
            WeatherConditionType.CLEAR to IconId.WEATHER_CLEAR,
            WeatherConditionType.CLOUDY to IconId.WEATHER_CLOUDY,
            WeatherConditionType.SUN_AND_CLOUD to IconId.WEATHER_SUN_AND_CLOUD,
            WeatherConditionType.RAIN to IconId.WEATHER_RAIN,
            WeatherConditionType.STORM to IconId.WEATHER_STORM,
            WeatherConditionType.SNOW to IconId.WEATHER_SNOW,
            WeatherConditionType.FOG to IconId.WEATHER_FOG,
            WeatherConditionType.UNKNOWN to IconId.WEATHER_CLEAR
        )

        expected.forEach { (condition, iconId) ->
            assertEquals("$condition keeps its icon", iconId, condition.iconId)
        }
        assertEquals(9, WeatherConditionType.entries.size)
    }
}
