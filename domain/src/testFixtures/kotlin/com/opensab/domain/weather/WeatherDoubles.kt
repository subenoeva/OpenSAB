package com.opensab.domain.weather

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** **Owner: the test that constructs it.** */
class MockWeatherSource : WeatherSource {
    private val _currentWeather = MutableStateFlow<WeatherInfo?>(null)
    override val currentWeather: StateFlow<WeatherInfo?> = _currentWeather.asStateFlow()

    var refreshCalls = 0
    var startCalls = 0
    var stopCalls = 0

    fun updateWeather(info: WeatherInfo?) {
        _currentWeather.value = info
    }

    override fun refresh() {
        refreshCalls++
    }

    override fun start(scope: CoroutineScope) {
        startCalls++
    }

    override fun stop() {
        stopCalls++
    }
}
