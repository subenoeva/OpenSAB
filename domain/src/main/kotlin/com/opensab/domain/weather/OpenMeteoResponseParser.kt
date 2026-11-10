package com.opensab.domain.weather

import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.opensab.logging.Log

/**
 * One current-weather reading, before it knows where it was taken or who asked for it.
 *
 * It is deliberately not a [WeatherInfo]: the locality comes from the fix and the timestamp from
 * the clock, and neither belongs to the parser.
 */
internal data class WeatherObservation(
    val temperature: Int,
    val unitSymbol: String,
    val condition: WmoCondition
)

/**
 * One slot of the `hourly` block, before it knows where it was taken.
 *
 * [epochSeconds] and not a date: the request asks for `timeformat=unixtime` precisely so that "how
 * far away is this slot" stays a subtraction and no time zone has to be parsed.
 */
internal data class HourlySample(
    val epochSeconds: Long,
    val temperature: Int,
    val condition: WmoCondition
)

/**
 * Reads the `current` block of an Open-Meteo forecast response.
 *
 * Parsed against the JSON tree rather than a generated `@Serializable` mirror of the whole
 * response: the two fields that matter are three levels of optional blocks away, and a schema
 * class would turn every field the API adds into a decoding failure.
 */
internal object OpenMeteoResponseParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** null when the body is not a usable current-weather response. */
    fun parse(body: String): WeatherObservation? {
        val root: JsonObject = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            Log.w(e, "Open-Meteo response is not a JSON object")
            return null
        }

        return try {
            val current = root["current"]?.jsonObject ?: return null
            val temperature = current["temperature_2m"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: return null
            val code = current["weather_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null

            // A reading without its units block is still a reading: the scale degrades to a bare
            // degree sign instead of throwing the temperature away.
            val unitSymbol = root["current_units"]
                ?.jsonObject
                ?.get("temperature_2m")
                ?.jsonPrimitive
                ?.content
                ?: "°"

            WeatherObservation(
                temperature = temperature.roundToInt(),
                unitSymbol = unitSymbol,
                condition = WmoConditionMapper.map(code)
            )
        } catch (e: Exception) {
            Log.w(e, "Open-Meteo response has no usable current block")
            null
        }
    }

    /**
     * Reads the `hourly` block, which arrives as three arrays to be walked index by index.
     *
     * Empty when there is no usable one, and that is not a failure: a response with only `current`
     * is still a reading, and it is exactly what a phone whose cache was written before this
     * existed restores. Same reason the arrays are truncated to the shortest of the three
     * instead of trusted to be equal — an index out of range is the one way this could throw, and
     * a short array is a truncated forecast, not a lost reading.
     */
    fun parseHourly(body: String): List<HourlySample> {
        val root: JsonObject = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            Log.w(e, "Open-Meteo response is not a JSON object")
            return emptyList()
        }

        return try {
            val hourly = root["hourly"]?.jsonObject ?: return emptyList()
            val times = hourly["time"]?.jsonArray ?: return emptyList()
            val temperatures = hourly["temperature_2m"]?.jsonArray ?: return emptyList()
            val codes = hourly["weather_code"]?.jsonArray ?: return emptyList()

            // A slot the API has no value for arrives as `null`, and `null` does not parse into a
            // number: one hole costs its own slot and none of the ones around it.
            (0 until minOf(times.size, temperatures.size, codes.size)).mapNotNull { index ->
                val epochSeconds = times[index].jsonPrimitive.content.toLongOrNull()
                val temperature = temperatures[index].jsonPrimitive.content.toDoubleOrNull()
                val code = codes[index].jsonPrimitive.content.toIntOrNull()
                if (epochSeconds == null || temperature == null || code == null) {
                    null
                } else {
                    HourlySample(
                        epochSeconds = epochSeconds,
                        temperature = temperature.roundToInt(),
                        condition = WmoConditionMapper.map(code)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(e, "Open-Meteo response has no usable hourly block")
            emptyList()
        }
    }
}
