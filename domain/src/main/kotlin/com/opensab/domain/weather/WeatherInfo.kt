package com.opensab.domain.weather

import kotlinx.serialization.Serializable

enum class WeatherConditionType {
    SUNNY,
    CLEAR,
    CLOUDY,
    SUN_AND_CLOUD,
    RAIN,
    STORM,
    SNOW,
    FOG,
    UNKNOWN
}

/**
 * One slot of the forecast. [timestamp] is epoch millis, so how far away this slot is stays a
 * subtraction: the request asks for `timeformat=unixtime` precisely so no time zone is parsed.
 */
@Serializable
data class WeatherForecastSegment(
    val timestamp: Long,
    val temperature: Int,
    /**
     * Read and never written, exactly like [WeatherInfo.condition]: the English sentence older
     * versions cached, kept so a stored slot still decodes.
     */
    val condition: String = "",
    /** Named rather than written: it gets a language only when painted. */
    val caption: WmoCaption = WmoCaption.UNKNOWN,
    val type: WeatherConditionType = WeatherConditionType.SUNNY
)

/**
 * Serializable because the last good reading outlives the process: it is what opens the carousel
 * entry on the first frame of a session (`DataStoreWeatherCache`).
 */
@Serializable
data class WeatherInfo(
    val locationName: String,
    val temperature: Int,
    val temperatureUnit: String = "°C",
    /**
     * **Read and never written.** The English sentence older versions stored, kept only so a
     * reading already on a phone still decodes; nothing produces it any more, and because
     * `kotlinx.serialization` leaves out a field holding its default, a reading written now
     * carries no English at all.
     */
    val condition: String = "",
    /**
     * What the sky is called, named rather than written: this is the one piece of cluster copy
     * that outlives the process, so it goes to disk as a token and gets
     * a language only when [com.opensab.cluster.text.WeatherCaptionResolver] paints it.
     */
    val caption: WmoCaption = WmoCaption.UNKNOWN,
    val type: WeatherConditionType = WeatherConditionType.SUNNY,
    /**
     * The next few hours. **Empty is a valid reading and not a missing one**: an entry written
     * before this field existed loads with it empty, and the carousel entry must not close for
     * that — `canEnter` hangs on the reading being non-null and on nothing else.
     */
    val hourly: List<WeatherForecastSegment> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    /** Where the reading was taken; null in a reading stored before it was saved. */
    val latitude: Double? = null,
    val longitude: Double? = null
)
