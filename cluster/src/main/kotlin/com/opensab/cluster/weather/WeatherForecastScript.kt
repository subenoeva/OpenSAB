package com.opensab.cluster.weather

import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechQuantity
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.speech.SpeechWord
import com.opensab.domain.weather.WeatherConditionType
import com.opensab.domain.weather.WeatherInfo
import kotlin.math.roundToInt

/**
 * What the weather page says out loud, as named phrases: the locality, the reading in front of the
 * rider and one line per forecast slot.
 *
 * Pure on purpose: the words belong to the platform half and the schedule to the engine; what is
 * decided here is **what is said, in which order, and what is left unsaid**.
 *
 * @param nowMillis the instant the script is built at. The hour of each line is counted from here
 *   and not from when the reading was fetched: a cached reading restored an hour later says one
 *   hour less, because that is the truth.
 */
internal fun forecastScript(info: WeatherInfo?, nowMillis: Long): List<SpeechRequest> {
    if (info == null) return emptyList()

    val script = mutableListOf<SpeechRequest>()

    // Best effort, so it can be missing, and "the weather in" on its own is worse than silence.
    if (info.locationName.isNotEmpty()) {
        script += SpeechRequest(
            SpeechToken.WEATHER_FORECAST_MESSAGE,
            SpeechArg.Text(info.locationName)
        )
    }

    script += SpeechRequest(
        SpeechToken.WEATHER_CURRENT_LINE,
        SpeechArg.Word(info.type.speechWord()),
        SpeechArg.Quantity(SpeechQuantity.DEGREES, info.temperature)
    )

    for (segment in info.hourly) {
        val hours = hoursFromNow(segment.timestamp, nowMillis)
        // A reading restored from the cache can be old enough for its first slots to have gone by,
        // and a line about an hour that already happened is worse than one line fewer.
        if (hours <= 0) continue

        script += SpeechRequest(
            SpeechToken.WEATHER_FORECAST_LINE,
            SpeechArg.Quantity(SpeechQuantity.HOURS, hours),
            SpeechArg.Word(segment.type.speechWord()),
            SpeechArg.Quantity(SpeechQuantity.DEGREES, segment.temperature)
        )
    }

    return script
}

/** Rounded, because a slot two hours and fifty-eight minutes out is three hours out to a rider. */
private fun hoursFromNow(timestamp: Long, nowMillis: Long): Int =
    ((timestamp - nowMillis).toDouble() / 3_600_000.0).roundToInt()

/**
 * Exhaustive and deliberately without an `else`: a condition type added to the catalogue must not
 * compile until somebody has decided how it is spoken. Keyed on the type and not on the WMO
 * caption, because the captions are screen vocabulary.
 */
fun WeatherConditionType.speechWord(): SpeechWord = when (this) {
    WeatherConditionType.SUNNY -> SpeechWord.WEATHER_SUNNY
    WeatherConditionType.CLEAR -> SpeechWord.WEATHER_CLEAR
    WeatherConditionType.CLOUDY -> SpeechWord.WEATHER_CLOUDY
    WeatherConditionType.SUN_AND_CLOUD -> SpeechWord.WEATHER_SUN_AND_CLOUD
    WeatherConditionType.RAIN -> SpeechWord.WEATHER_RAIN
    WeatherConditionType.STORM -> SpeechWord.WEATHER_STORM
    WeatherConditionType.SNOW -> SpeechWord.WEATHER_SNOW
    WeatherConditionType.FOG -> SpeechWord.WEATHER_FOG
    WeatherConditionType.UNKNOWN -> SpeechWord.WEATHER_UNKNOWN
}
