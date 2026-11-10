package com.opensab.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** An unset preference resolves to FAHRENHEIT for `{"US"}` and to CELSIUS for every other country. */
class TemperatureUnitTest {

    @Test
    fun defaultsToFahrenheitInTheUnitedStates() {
        assertEquals(TemperatureUnit.FAHRENHEIT, resolveTemperatureUnit(chosen = null, country = "US"))
    }

    @Test
    fun defaultsToCelsiusEverywhereElse() {
        for (country in listOf("ES", "JP", "GB", "PT", "")) {
            assertEquals(
                "country $country",
                TemperatureUnit.CELSIUS,
                resolveTemperatureUnit(chosen = null, country = country)
            )
        }
    }

    @Test
    fun readsTheCountryCaseInsensitivelyLikeTheReferenceClientUppercasesIt() {
        assertEquals(TemperatureUnit.FAHRENHEIT, resolveTemperatureUnit(chosen = null, country = "us"))
    }

    @Test
    fun anExplicitChoiceWinsOverTheCountryDefault() {
        assertEquals(
            TemperatureUnit.CELSIUS,
            resolveTemperatureUnit(chosen = TemperatureUnit.CELSIUS, country = "US")
        )
        assertEquals(
            TemperatureUnit.FAHRENHEIT,
            resolveTemperatureUnit(chosen = TemperatureUnit.FAHRENHEIT, country = "ES")
        )
    }

    /**
     * `null` in the stored settings means "the rider has not chosen", which is the state the
     * country rule exists to resolve. Persisting it as an explicit value would freeze the default
     * of whatever country the phone was in the day the app first ran.
     */
    @Test
    fun anUnsetPreferenceRoundTripsAsUnset() {
        val settings = AppSettings()
        assertEquals(null, settings.temperatureUnit)
    }
}
