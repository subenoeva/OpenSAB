package com.opensab.app.di

import com.opensab.domain.call.CallNotificationBus
import com.opensab.domain.message.MessageNotificationBus
import com.opensab.domain.nav.NavigationNotificationBus
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Single owner of the three notification buses.
 *
 * `OpenSabNotificationListenerService` is Android-instantiated, so it cannot receive a constructor
 * argument the way every other class in the graph does — but that does not make the three buses
 * it posts through globals. They are `@Inject lateinit var` on the listener and
 * `@Provides @Singleton` here, the one place that builds them: every other reader takes its bus
 * through its own constructor, first parameter, like
 * [com.opensab.domain.call.DefaultCallManager] does.
 */
@Module
@InstallIn(SingletonComponent::class)
object NotificationModule {

    @Provides
    @Singleton
    fun provideCallNotificationBus(): CallNotificationBus = CallNotificationBus()

    @Provides
    @Singleton
    fun provideMessageNotificationBus(): MessageNotificationBus = MessageNotificationBus()

    @Provides
    @Singleton
    fun provideNavigationNotificationBus(): NavigationNotificationBus = NavigationNotificationBus()
}
