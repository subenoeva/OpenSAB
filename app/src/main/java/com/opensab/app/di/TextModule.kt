package com.opensab.app.di

import android.content.Context
import com.opensab.app.source.text.ResourceClusterTextResolver
import com.opensab.cluster.text.ClusterPresentation
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.WeatherCaptionResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The words the cluster paints.
 *
 * One instance behind the port, and a singleton for the same reason `SpeechModule`'s resolver is:
 * it holds the application context and nothing else, and two of them would be two objects doing
 * one job.
 */
@Module
@InstallIn(SingletonComponent::class)
object TextModule {

    @Provides
    @Singleton
    fun provideResourceClusterTextResolver(
        @ApplicationContext context: Context
    ): ResourceClusterTextResolver = ResourceClusterTextResolver(context)

    @Provides
    fun provideClusterTextResolver(
        resolver: ResourceClusterTextResolver
    ): ClusterTextResolver = resolver

    @Provides
    fun provideWeatherCaptionResolver(
        resolver: ResourceClusterTextResolver
    ): WeatherCaptionResolver = resolver

    @Provides
    @Singleton
    fun provideClusterPresentation(
        resolver: ResourceClusterTextResolver
    ): ClusterPresentation = ClusterPresentation(
        text = resolver,
        captions = resolver
    )
}

