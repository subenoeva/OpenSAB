package com.opensab.domain.settings

/** [apiValue] is the `temperature_unit` query parameter Open-Meteo takes. */
enum class TemperatureUnit(val symbol: String, val apiValue: String) {
    CELSIUS("°C", "celsius"),
    FAHRENHEIT("°F", "fahrenheit")
}

/**
 * With nothing chosen: FAHRENHEIT for the United States and CELSIUS everywhere else, off the
 * locale's country uppercased.
 */
fun resolveTemperatureUnit(chosen: TemperatureUnit?, country: String): TemperatureUnit {
    if (chosen != null) return chosen
    return if (country.uppercase() in FAHRENHEIT_COUNTRIES) {
        TemperatureUnit.FAHRENHEIT
    } else {
        TemperatureUnit.CELSIUS
    }
}

private val FAHRENHEIT_COUNTRIES = setOf("US")
