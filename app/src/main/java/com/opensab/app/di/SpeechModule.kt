package com.opensab.app.di

import android.content.Context
import com.opensab.app.service.ForegroundServiceGrant
import com.opensab.app.source.speech.AndroidBluetoothVoiceRouter
import com.opensab.app.source.speech.AndroidSpeechRecognizer
import com.opensab.app.source.speech.AndroidSpeechSynthesizer
import com.opensab.app.source.speech.ResourceSpeechTextResolver
import com.opensab.cluster.PageVoice
import com.opensab.domain.audio.AudioFocus
import com.opensab.domain.call.CallManager
import com.opensab.domain.call.CallStatus
import com.opensab.domain.settings.SettingsRepository
import com.opensab.domain.settings.VoiceVerbosity
import com.opensab.domain.speech.BluetoothVoiceRouter
import com.opensab.domain.speech.MicrophoneGatedSpeechRecognizer
import com.opensab.domain.speech.SpeechRecognizer
import com.opensab.domain.speech.SpeechSynthesizer
import com.opensab.domain.speech.SpeechTextResolver
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
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

/** The rider's speech verbosity setting, read synchronously at the instant of speech. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceVerbositySetting

@Module
@InstallIn(SingletonComponent::class)
object SpeechModule {

    /**
     * The one thing in the engine's path that reads a string.
     *
     * It takes the holder's flow rather than a snapshot because the motorcycle is identified long
     * after the graph is built, and the cluster is what decides how a phrase is worded.
     */
    @Provides
    @Singleton
    fun provideSpeechTextResolver(
        @ApplicationContext context: Context,
        capabilitiesHolder: VehicleCapabilitiesHolder
    ): SpeechTextResolver = ResourceSpeechTextResolver(context, capabilitiesHolder.state)

    /**
     * The voice of the session. A singleton because the `TextToSpeech` engine behind it is bound
     * once and kept: re-initializing costs seconds and the rider is already moving.
     */
    @Provides
    @Singleton
    fun provideSpeechSynthesizer(
        @ApplicationContext context: Context
    ): SpeechSynthesizer = AndroidSpeechSynthesizer(context)

    /**
     * The ear of the session. A singleton for symmetry and not for state: it keeps none, and
     * creates and destroys a recognizer per listen.
     *
     * Gated by the granted foreground type: a service without `microphone` would record zeros.
     */
    @Provides
    @Singleton
    fun provideSpeechRecognizer(
        @ApplicationContext context: Context,
        audioFocus: AudioFocus,
        voiceRouter: BluetoothVoiceRouter,
        grant: ForegroundServiceGrant
    ): SpeechRecognizer = MicrophoneGatedSpeechRecognizer(
        AndroidSpeechRecognizer(context, audioFocus, voiceRouter),
        grant::microphoneUsable
    )

    @Provides
    @Singleton
    fun provideBluetoothVoiceRouter(
        @ApplicationContext context: Context
    ): BluetoothVoiceRouter = AndroidBluetoothVoiceRouter(context)

    @Provides
    @Singleton
    @VoiceVerbositySetting
    fun provideVoiceVerbositySetting(
        settingsRepository: SettingsRepository,
        applicationScope: CoroutineScope
    ): StateFlow<VoiceVerbosity> = settingsRepository.settingsFlow
        .map { it.voiceVerbosity }
        .stateIn(applicationScope, SharingStarted.Eagerly, VoiceVerbosity.STANDARD)

    /**
     * The two ports joined into the one thing the engine is allowed to hold.
     *
     * A singleton because the voice it drives is one: two of these would be two owners of a single
     * speaker.
     */
    @Provides
    @Singleton
    fun providePageVoice(
        resolver: SpeechTextResolver,
        synthesizer: SpeechSynthesizer,
        audioFocus: AudioFocus,
        @VoiceVerbositySetting verbosityFlow: @JvmSuppressWildcards StateFlow<VoiceVerbosity>,
        applicationScope: CoroutineScope,
        callManager: CallManager
    ): PageVoice = PageVoice(
        resolver = resolver,
        synthesizer = synthesizer,
        audioFocus = audioFocus,
        verbosity = { verbosityFlow.value },
        sessionScope = applicationScope,
        inCall = { callManager.activeCall.value?.status == CallStatus.ACTIVE }
    )
}
