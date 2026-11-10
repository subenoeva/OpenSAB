package com.opensab.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.opensab.app.persistence.DataStoreFavoriteMediaRepository
import com.opensab.app.source.music.AndroidAppOpenerSeam
import com.opensab.app.source.music.AndroidAudioKeyDispatcher
import com.opensab.app.source.music.AndroidMediaAppLauncher
import com.opensab.app.source.music.AndroidMediaAppScanner
import com.opensab.app.source.music.AndroidMediaBrowserClientFactory
import com.opensab.app.source.music.AndroidMediaLibraryBrowser
import com.opensab.app.source.music.AndroidWakeLockSeam
import com.opensab.app.source.music.AppOpenerSeam
import com.opensab.app.source.music.AudioKeyDispatcher
import com.opensab.app.source.music.AudioManagerVolumeSource
import com.opensab.app.source.music.FavoriteMediaRepository
import com.opensab.app.source.music.MediaAppLauncher
import com.opensab.app.source.music.MediaAppScanner
import com.opensab.app.source.music.MediaBrowserClientFactory
import com.opensab.app.source.music.MediaBrowserConnectionOverride
import com.opensab.app.source.music.MediaSessionManagerSource
import com.opensab.app.source.music.SystemMediaControllerAdapter
import com.opensab.app.source.music.WakeLockSeam
import com.opensab.domain.music.MediaControllerAdapter
import com.opensab.domain.music.MediaLibraryBrowser
import com.opensab.domain.music.MediaSessionSource
import com.opensab.domain.music.MusicManager
import com.opensab.domain.music.MuteGatedVolumeSource
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.settings.MuteAction
import com.opensab.domain.settings.SettingsRepository
import com.opensab.domain.settings.coldStartMediaIdFor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MuteActionSetting

/** "Handlebar Volume Control": whether the vertical keys change the phone volume on the music and volume pages. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HandlebarVolumeControl

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides
    @Singleton
    fun provideFavoriteMediaRepository(
        dataStore: DataStore<Preferences>
    ): FavoriteMediaRepository = DataStoreFavoriteMediaRepository(dataStore)

    @Provides
    @Singleton
    fun provideMediaAppScanner(
        @ApplicationContext context: Context
    ): MediaAppScanner = AndroidMediaAppScanner(context)

    @Provides
    @Singleton
    fun provideWakeLockSeam(
        @ApplicationContext context: Context
    ): WakeLockSeam = AndroidWakeLockSeam(context)

    @Provides
    @Singleton
    fun provideMediaBrowserClientFactory(
        @ApplicationContext context: Context,
        override: MediaBrowserConnectionOverride
    ): MediaBrowserClientFactory = AndroidMediaBrowserClientFactory(context, override)

    @Provides
    @Singleton
    fun provideAudioKeyDispatcher(
        @ApplicationContext context: Context
    ): AudioKeyDispatcher = AndroidAudioKeyDispatcher(context)

    @Provides
    @Singleton
    fun provideAppOpenerSeam(
        @ApplicationContext context: Context
    ): AppOpenerSeam = AndroidAppOpenerSeam(context)

    @Provides
    @Singleton
    fun provideMediaSessionSource(
        @ApplicationContext context: Context
    ): MediaSessionSource = MediaSessionManagerSource(context)

    @Provides
    @Singleton
    fun provideMediaLibraryBrowser(
        @ApplicationContext context: Context
    ): MediaLibraryBrowser = AndroidMediaLibraryBrowser(context)

    @Provides
    @Singleton
    fun provideMediaAppLauncher(
        wakeLockSeam: WakeLockSeam,
        browserFactory: MediaBrowserClientFactory,
        audioKeyDispatcher: AudioKeyDispatcher,
        applicationScope: CoroutineScope,
        appOpener: AppOpenerSeam,
        sessionSource: MediaSessionSource,
        settingsRepository: SettingsRepository
    ): MediaAppLauncher = AndroidMediaAppLauncher(
        wakeLockSeam = wakeLockSeam,
        browserFactory = browserFactory,
        audioKeyDispatcher = audioKeyDispatcher,
        coroutineScope = applicationScope,
        dispatcher = Dispatchers.Main, // Dispatchers.Main required for MediaBrowserCompat internal Looper
        appOpener = appOpener,
        sessionSource = sessionSource,
        coldStartMediaIdProvider = { packageName ->
            // Read on the spot: a cold start is rare and the settings flow is DataStore-backed,
            // so caching it here would only add a way to dispatch a stale pin.
            settingsRepository.settingsFlow.first().coldStartMediaIdFor(packageName)
        }
    )

    @Provides
    @Singleton
    @MuteActionSetting
    fun provideMuteActionSetting(
        settingsRepository: SettingsRepository,
        applicationScope: CoroutineScope
    ): StateFlow<MuteAction> = settingsRepository.settingsFlow
        .map { it.muteAction }
        .stateIn(applicationScope, SharingStarted.Eagerly, MuteAction.PAUSE_MUSIC)

    @Provides
    @Singleton
    @HandlebarVolumeControl
    fun provideHandlebarVolumeControl(
        settingsRepository: SettingsRepository,
        applicationScope: CoroutineScope
    ): StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.handlebarVolumeControl }
        .stateIn(applicationScope, SharingStarted.Eagerly, true)

    @Provides
    @Singleton
    fun provideVolumeSource(
        @ApplicationContext context: Context,
        @MuteActionSetting muteActionFlow: @JvmSuppressWildcards StateFlow<MuteAction>,
        musicManagerProvider: javax.inject.Provider<MusicManager>
    ): VolumeSource = MuteGatedVolumeSource(
        delegate = AudioManagerVolumeSource(context),
        isPauseMode = { muteActionFlow.value == MuteAction.PAUSE_MUSIC },
        togglePlayback = { musicManagerProvider.get().togglePlayPause() }
    )

    @Provides
    @Singleton
    fun provideMediaControllerAdapter(
        sessionSource: MediaSessionSource,
        volumeSource: VolumeSource,
        favoriteMediaRepository: FavoriteMediaRepository,
        mediaAppLauncher: MediaAppLauncher,
        mediaAppScanner: MediaAppScanner,
        audioKeyDispatcher: AudioKeyDispatcher,
        applicationScope: CoroutineScope
    ): MediaControllerAdapter = SystemMediaControllerAdapter(
        sessionSource = sessionSource,
        volumeSource = volumeSource,
        favoriteRepository = favoriteMediaRepository,
        appLauncher = mediaAppLauncher,
        appScanner = mediaAppScanner,
        audioKeyDispatcher = audioKeyDispatcher,
        applicationScope = applicationScope
    )
}
