package com.opensab.domain.weather

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface WeatherSource {
    val currentWeather: StateFlow<WeatherInfo?>

    fun refresh()

    /** Starts the periodic refresh on the session scope: it lives only while a session does. */
    fun start(scope: CoroutineScope)

    /**
     * Releases the session scope too, not just the timer: [start] is a no-op while a scope is held,
     * so otherwise the second session of a process would get no weather.
     */
    fun stop()
}
