package com.opensab.domain.weather

import com.opensab.domain.settings.TemperatureUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class OpenMeteoUrlBuilderTest {

    @Test
    fun buildsTheCurrentWeatherQueryForACoordinate() {
        val url = OpenMeteoUrlBuilder.currentWeather(
            latitude = 40.42,
            longitude = -3.70,
            unit = TemperatureUnit.CELSIUS
        )

        assertEquals(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=40.42&longitude=-3.70" +
                "&current=temperature_2m,weather_code" +
                "&hourly=temperature_2m,weather_code" +
                "&forecast_days=2" +
                "&timeformat=unixtime" +
                "&temperature_unit=celsius",
            url
        )
    }

    /**
     * The page sings the hours ahead: the reading the carousel already shows is exactly what the
     * rider sees without entering, so a page that only repeated it would have nothing to say.
     */
    @Test
    fun theRequestAsksForTheHourlyBlockAsWellAsTheCurrentOne() {
        val url = OpenMeteoUrlBuilder.currentWeather(1.0, 2.0, TemperatureUnit.CELSIUS)

        assertEquals(true, url.contains("&current=temperature_2m,weather_code"))
        assertEquals(true, url.contains("&hourly=temperature_2m,weather_code"))
    }

    /**
     * The only thing the script needs from a slot is how many hours away it is, and with epochs
     * that is a subtraction. Asking for ISO-8601 would mean parsing dates and reasoning about
     * `timezone=auto`, which is a source of defects in exchange for nothing.
     */
    @Test
    fun theRequestAsksForUnixTimestampsSoNoTimeZoneHasToBeParsed() {
        val url = OpenMeteoUrlBuilder.currentWeather(1.0, 2.0, TemperatureUnit.CELSIUS)

        assertEquals(true, url.contains("&timeformat=unixtime"))
        assertEquals(false, url.contains("timezone="))
    }

    /** At 23:00 the slot nine hours ahead falls on the next day, and with one day it would not exist. */
    @Test
    fun theRequestAsksForTwoDaysSoNineHoursAheadAlwaysExists() {
        val url = OpenMeteoUrlBuilder.currentWeather(1.0, 2.0, TemperatureUnit.CELSIUS)

        assertEquals(true, url.contains("&forecast_days=2"))
    }

    /**
     * The coordinate leaves the phone, so it leaves rounded: two decimals is about 1.1 km, which
     * is exact enough for weather and stops publishing which door the rider parks at.
     */
    @Test
    fun roundsCoordinatesToTwoDecimalsBeforeLeavingThePhone() {
        val url = OpenMeteoUrlBuilder.currentWeather(
            latitude = 40.416775,
            longitude = -3.703790,
            unit = TemperatureUnit.CELSIUS
        )

        assertEquals(true, url.contains("latitude=40.42&longitude=-3.70"))
        assertEquals(false, url.contains("40.416775"))
    }

    @Test
    fun carriesTheTemperatureUnitOfThePreference() {
        val celsius = OpenMeteoUrlBuilder.currentWeather(1.0, 2.0, TemperatureUnit.CELSIUS)
        val fahrenheit = OpenMeteoUrlBuilder.currentWeather(1.0, 2.0, TemperatureUnit.FAHRENHEIT)

        assertEquals(true, celsius.endsWith("&temperature_unit=celsius"))
        assertEquals(true, fahrenheit.endsWith("&temperature_unit=fahrenheit"))
    }

    /**
     * A Spanish or German phone formats 40.42 as `40,42`, which Open-Meteo answers 400 to. The
     * number goes on a wire, so it is formatted for the wire and not for the rider.
     */
    @Test
    fun formatsCoordinatesForTheWireAndNotForThePhoneLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val url = OpenMeteoUrlBuilder.currentWeather(40.42, -3.70, TemperatureUnit.CELSIUS)

            assertEquals(true, url.contains("latitude=40.42&longitude=-3.70"))
            assertEquals(false, url.contains("40,42"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
