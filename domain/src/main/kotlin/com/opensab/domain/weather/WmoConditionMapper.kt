package com.opensab.domain.weather

/** An icon family and a caption. */
data class WmoCondition(
    val type: WeatherConditionType,
    val caption: WmoCaption
)

/**
 * Maps a WMO weather code — the vocabulary Open-Meteo answers in — to [WeatherConditionType].
 *
 * CLEAR is unreachable here on purpose: it is the night-time clear sky, and day and night are not
 * modelled, so a clear sky is always SUNNY.
 */
object WmoConditionMapper {

    fun map(code: Int): WmoCondition = when (code) {
        0 -> WmoCondition(WeatherConditionType.SUNNY, WmoCaption.CLEAR)
        1 -> WmoCondition(WeatherConditionType.SUN_AND_CLOUD, WmoCaption.MAINLY_CLEAR)
        2 -> WmoCondition(WeatherConditionType.SUN_AND_CLOUD, WmoCaption.PARTLY_CLOUDY)
        3 -> WmoCondition(WeatherConditionType.CLOUDY, WmoCaption.OVERCAST)
        45 -> WmoCondition(WeatherConditionType.FOG, WmoCaption.FOG)
        48 -> WmoCondition(WeatherConditionType.FOG, WmoCaption.RIME_FOG)
        51, 53, 55 -> WmoCondition(WeatherConditionType.RAIN, WmoCaption.DRIZZLE)
        56, 57 -> WmoCondition(WeatherConditionType.RAIN, WmoCaption.FREEZING_DRIZZLE)
        61, 63, 65 -> WmoCondition(WeatherConditionType.RAIN, WmoCaption.RAIN)
        66, 67 -> WmoCondition(WeatherConditionType.RAIN, WmoCaption.FREEZING_RAIN)
        71, 73, 75 -> WmoCondition(WeatherConditionType.SNOW, WmoCaption.SNOW)
        77 -> WmoCondition(WeatherConditionType.SNOW, WmoCaption.SNOW_GRAINS)
        80, 81, 82 -> WmoCondition(WeatherConditionType.RAIN, WmoCaption.RAIN_SHOWERS)
        85, 86 -> WmoCondition(WeatherConditionType.SNOW, WmoCaption.SNOW_SHOWERS)
        95 -> WmoCondition(WeatherConditionType.STORM, WmoCaption.THUNDERSTORM)
        96, 99 -> WmoCondition(WeatherConditionType.STORM, WmoCaption.THUNDERSTORM_WITH_HAIL)
        else -> WmoCondition(WeatherConditionType.UNKNOWN, WmoCaption.UNKNOWN)
    }
}
