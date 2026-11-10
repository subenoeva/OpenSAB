package com.opensab.app.di

import android.content.Context
import com.opensab.app.source.nav.AndroidDeviceUnlockSource
import com.opensab.app.source.nav.AndroidNavigationLauncher
import com.opensab.app.source.nav.MapsArrowCatalog
import com.opensab.domain.nav.DeviceUnlockSource
import com.opensab.domain.nav.NavigationLauncher
import com.opensab.domain.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The rider's "Intercept GPS Notifications", as a flow the listener service reads on every notification. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InterceptGpsNotifications

@Module
@InstallIn(SingletonComponent::class)
object NavModule {

    @Provides
    @Singleton
    fun provideNavigationLauncher(@ApplicationContext context: Context): NavigationLauncher =
        AndroidNavigationLauncher(context)

    @Provides
    @Singleton
    fun provideDeviceUnlockSource(@ApplicationContext context: Context): DeviceUnlockSource =
        AndroidDeviceUnlockSource(context)

    @Provides
    @Singleton
    fun provideMapsArrowCatalog(@ApplicationContext context: Context): MapsArrowCatalog =
        MapsArrowCatalog(context)

    @Provides
    @Singleton
    @InterceptGpsNotifications
    fun provideInterceptGpsNotifications(
        settingsRepository: SettingsRepository,
        applicationScope: CoroutineScope
    ): StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.interceptGpsNotifications }
        .stateIn(applicationScope, SharingStarted.Eagerly, true)
}
