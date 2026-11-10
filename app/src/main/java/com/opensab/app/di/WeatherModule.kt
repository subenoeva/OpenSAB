package com.opensab.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.opensab.app.persistence.DataStoreWeatherCache
import com.opensab.app.source.weather.AndroidLocationSource
import com.opensab.app.source.weather.JdkHttpGetClient
import com.opensab.domain.settings.SettingsRepository
import com.opensab.domain.settings.Tunable
import com.opensab.domain.settings.resolveTemperatureUnit
import com.opensab.domain.weather.DefaultWeatherSource
import com.opensab.domain.weather.HttpGetClient
import com.opensab.domain.weather.LocationSource
import com.opensab.domain.weather.WeatherCache
import com.opensab.domain.weather.WeatherSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WeatherModule {

    @Provides
    @Singleton
    fun provideLocationSource(
        @ApplicationContext context: Context
    ): LocationSource = AndroidLocationSource(context)

    @Provides
    @Singleton
    fun provideHttpGetClient(): HttpGetClient = JdkHttpGetClient()

    @Provides
    @Singleton
    fun provideWeatherCache(
        dataStore: DataStore<Preferences>
    ): WeatherCache = DataStoreWeatherCache(dataStore, nowMillis = System::currentTimeMillis)

    /**
     * The one weather source of the session.
     *
     * The unit is resolved rather than read: a null preference means the rider never chose, and the
     * country decides — Fahrenheit in the United States and Celsius everywhere else.
     */
    @Provides
    @Singleton
    fun provideWeatherSource(
        locationSource: LocationSource,
        httpClient: HttpGetClient,
        cache: WeatherCache,
        settingsRepository: SettingsRepository
    ): WeatherSource = DefaultWeatherSource(
        locationSource = locationSource,
        httpClient = httpClient,
        cache = cache,
        temperatureUnit = settingsRepository.settingsFlow.map {
            resolveTemperatureUnit(it.temperatureUnit, Locale.getDefault().country)
        },
        nowMillis = System::currentTimeMillis,
        refreshInterval = settingsRepository.reader(Tunable.WEATHER_REFRESH)
    )
}
