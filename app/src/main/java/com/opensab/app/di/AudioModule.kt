package com.opensab.app.di

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService
import com.opensab.app.source.audio.AndroidAudioFocus
import com.opensab.app.source.audio.AndroidCuePlayer
import com.opensab.domain.audio.AudioFocus
import com.opensab.domain.audio.CuePlayer
import com.opensab.domain.audio.GatedCuePlayer
import com.opensab.domain.audio.HeadsetPresence
import com.opensab.domain.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Qualifier
import javax.inject.Singleton

/** The rider's earcon switch, read synchronously at the instant of each cue. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SoundCuesEnabled

/**
 * Audio focus, which is neither media nor speech nor telephony but is asked for by two of the
 * three: the voice of a spoken page ducks the music, and a live call pauses it.
 *
 * It has a module of its own because both consumers are on the other side of a seam from it and
 * neither owns it. What each owns is its **lease** — the focus itself is stateless here, and this
 * binding hands out one request per caller precisely so the two never share one.
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideAudioFocus(
        @ApplicationContext context: Context
    ): AudioFocus = AndroidAudioFocus(context.getSystemService<AudioManager>()!!)

    /**
     * The setting, already resolved to a synchronous read. `GatedCuePlayer` takes a `() -> Boolean`
     * and not a `SettingsRepository` so that `source/audio` never has to see
     * `com.opensab.domain.settings`.
     */
    @Provides
    @Singleton
    @SoundCuesEnabled
    fun provideSoundCuesEnabled(
        settingsRepository: SettingsRepository,
        applicationScope: CoroutineScope
    ): StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { it.soundCuesEnabled }
        .stateIn(applicationScope, SharingStarted.Eagerly, true)

    /**
     * The one player of the session, already wrapped in the decorator that decides whether a cue
     * sounds. Nothing downstream knows the setting exists.
     *
     * Read at the instant of each `play`, not captured once here: a switch flipped mid-ride governs
     * the next beep and not the next session.
     */
    @Provides
    @Singleton
    fun provideCuePlayer(
        player: AndroidCuePlayer,
        @SoundCuesEnabled enabled: StateFlow<Boolean>
    ): CuePlayer = GatedCuePlayer(player, player) { enabled.value }

    @Provides
    @Singleton
    fun provideAndroidCuePlayer(
        @ApplicationContext context: Context
    ): AndroidCuePlayer = AndroidCuePlayer(
        context.getSystemService<AudioManager>()!!,
        context.getSystemService<NotificationManager>()!!
    )

    /** The same player: the headset the link cue waits for is the one [CuePlayer] routes by. */
    @Provides
    fun provideHeadsetPresence(player: AndroidCuePlayer): HeadsetPresence = player
}
