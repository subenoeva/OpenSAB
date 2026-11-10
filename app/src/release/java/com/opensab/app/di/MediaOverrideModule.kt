package com.opensab.app.di

import com.opensab.app.source.music.MediaBrowserConnectionOverride
import com.opensab.app.source.music.NoMediaBrowserConnectionOverride
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object MediaOverrideModule {

    @Provides
    fun provideMediaBrowserConnectionOverride(): MediaBrowserConnectionOverride = NoMediaBrowserConnectionOverride
}
