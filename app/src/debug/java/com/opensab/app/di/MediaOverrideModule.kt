package com.opensab.app.di

import com.opensab.app.source.music.MediaBrowserConnectionOverride
import com.opensab.app.source.music.SimulatedMediaBrowserOverride
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaOverrideModule {

    @Provides
    @Singleton
    fun provideSimulatedMediaBrowserOverride(): SimulatedMediaBrowserOverride = SimulatedMediaBrowserOverride()

    @Provides
    fun provideMediaBrowserConnectionOverride(
        simulated: SimulatedMediaBrowserOverride
    ): MediaBrowserConnectionOverride = simulated
}
