package com.opensab.domain.weather

import com.opensab.domain.settings.TemperatureUnit
import java.util.Locale

/**
 * Builds the one request this feature makes. Open-Meteo, because no Honda server is involved and it
 * needs no key and no account.
 */
internal object OpenMeteoUrlBuilder {

    const val ENDPOINT: String = "https://api.open-meteo.com/v1/forecast"

    /**
     * The coordinate is rounded to two decimals — about 1.1 km — before it leaves the phone: exact
     * enough for weather, and it stops publishing the rider's doorstep.
     *
     * Formatted with [Locale.ROOT] because a Spanish or German default locale writes `40,42`,
     * which the API answers 400 to.
     *
     * `current` and `hourly` travel in the same request, because this is still the one request the
     * feature makes: the reading is what the carousel entry shows without entering, and the hours
     * ahead are what the page sings once the rider does.
     *
     * Two parameters are decisions and not defaults:
     *
     * - **`timeformat=unixtime`**, because the only thing the script needs from a slot is how many
     *   hours away it is, and with epochs that is a subtraction. ISO-8601 would mean parsing dates
     *   and reasoning about `timezone=auto` in exchange for nothing, and no wall clock is ever
     *   shown to the rider.
     * - **`forecast_days=2`**, because the last slot the page sings is nine hours ahead, and at
     *   23:00 that one falls on the next day.
     */
    fun currentWeather(latitude: Double, longitude: Double, unit: TemperatureUnit): String {
        val lat = String.format(Locale.ROOT, "%.2f", latitude)
        val lon = String.format(Locale.ROOT, "%.2f", longitude)
        return "$ENDPOINT?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code" +
            "&hourly=temperature_2m,weather_code" +
            "&forecast_days=2" +
            "&timeformat=unixtime" +
            "&temperature_unit=${unit.apiValue}"
    }
}
