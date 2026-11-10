package com.opensab.cluster.icons

import com.opensab.domain.weather.WeatherConditionType
import com.opensab.sab.protocol.projection.IconId

/** The cluster, not the source, decides the [IconId]. */
val WeatherConditionType.iconId: IconId
    get() = when (this) {
        WeatherConditionType.SUNNY -> IconId.WEATHER_SUNNY
        WeatherConditionType.CLEAR -> IconId.WEATHER_CLEAR
        WeatherConditionType.CLOUDY -> IconId.WEATHER_CLOUDY
        WeatherConditionType.SUN_AND_CLOUD -> IconId.WEATHER_SUN_AND_CLOUD
        WeatherConditionType.RAIN -> IconId.WEATHER_RAIN
        WeatherConditionType.STORM -> IconId.WEATHER_STORM
        WeatherConditionType.SNOW -> IconId.WEATHER_SNOW
        WeatherConditionType.FOG -> IconId.WEATHER_FOG
        WeatherConditionType.UNKNOWN -> IconId.WEATHER_CLEAR
    }
