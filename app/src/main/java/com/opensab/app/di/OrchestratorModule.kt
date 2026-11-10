package com.opensab.app.di

import com.opensab.app.source.music.FavoriteMediaRepository
import com.opensab.cluster.AutoResumeMusicParticipant
import com.opensab.cluster.BaseStateManager
import com.opensab.cluster.ClusterHandoverCoordinator
import com.opensab.cluster.DefaultClusterHandoverCoordinator
import com.opensab.cluster.DefaultPageProviderFactory
import com.opensab.cluster.EngineParticipant
import com.opensab.cluster.IndicatorsParticipant
import com.opensab.cluster.KnownVehicleParticipant
import com.opensab.cluster.PageProviderFactory
import com.opensab.cluster.PageVoice
import com.opensab.cluster.StartStopParticipant
import com.opensab.cluster.indicators.CallIndicator
import com.opensab.cluster.indicators.IndicatorTranslator
import com.opensab.cluster.indicators.MessageIndicator
import com.opensab.cluster.indicators.MusicIndicators
import com.opensab.cluster.indicators.NavigationIndicator
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.cluster.text.ClusterPresentation
import com.opensab.domain.audio.AudioFocus
import com.opensab.domain.audio.CuePlayer
import com.opensab.domain.call.CallDialer
import com.opensab.domain.call.CallManager
import com.opensab.domain.call.CallNotificationBus
import com.opensab.domain.call.DefaultCallManager
import com.opensab.domain.call.TelecomAdapter
import com.opensab.domain.contacts.CallMenuAccess
import com.opensab.domain.contacts.ContactDirectory
import com.opensab.domain.message.MessageMenuAccess
import com.opensab.domain.message.MessageSource
import com.opensab.domain.message.SmsSender
import com.opensab.domain.music.DefaultMusicManager
import com.opensab.domain.music.MediaControllerAdapter
import com.opensab.domain.music.MusicManager
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.music.shouldAutoResume
import com.opensab.domain.nav.DefaultNavigationManager
import com.opensab.domain.nav.DeviceUnlockSource
import com.opensab.domain.nav.NavigationLauncher
import com.opensab.domain.nav.NavigationManager
import com.opensab.domain.nav.NavigationNotificationBus
import com.opensab.domain.settings.RootMenuEntry
import com.opensab.domain.settings.SettingsRepository
import com.opensab.domain.settings.Tunable
import com.opensab.domain.settings.resolveRootMenu
import com.opensab.domain.settings.visibleEntries
import com.opensab.domain.speech.SpeechRecognizer
import com.opensab.domain.weather.WeatherSource
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.LiveSessionLinkHolder
import com.opensab.sab.session.SessionParticipant
import com.opensab.sab.session.crypto.DefaultSecuritySessionManager
import com.opensab.sab.session.crypto.SecureKeyStore
import com.opensab.sab.session.crypto.SecuritySessionManager
import com.opensab.sab.session.input.DefaultInputDispatcher
import com.opensab.sab.session.input.InputDispatcher
import com.opensab.sab.session.input.SabModeRetry
import com.opensab.sab.session.input.SabModeWatchdog
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.AsciiFoldingTextSanitizer
import com.opensab.sab.session.projection.ControlPointGateway
import com.opensab.sab.session.projection.DefaultControlPointGateway
import com.opensab.sab.session.projection.DefaultScreenProjector
import com.opensab.sab.session.projection.PolicyScreenProjector
import com.opensab.sab.session.projection.ScreenProjector
import com.opensab.sab.session.projection.TextSanitizer
import com.opensab.sab.session.projection.VehicleFramePolicy
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import com.opensab.sab.session.vehicle.VehicleRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.time.Duration

/** The visible root menu entries in the rider's order. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RootMenuEntries

@Module
@InstallIn(SingletonComponent::class)
object OrchestratorModule {

    /**
     * The one owner of the carousel's layout: resolved once, here, from the stored names. Nothing
     * downstream filters or orders again.
     */
    @Provides
    @Singleton
    @RootMenuEntries
    fun provideRootMenuEntries(
        settingsRepository: SettingsRepository,
        applicationScope: CoroutineScope
    ): StateFlow<List<RootMenuEntry>> = settingsRepository.settingsFlow
        .map { resolveRootMenu(it.rootMenuOrder, it.rootMenuHidden).visibleEntries() }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, RootMenuEntry.entries.toList())

    @Provides
    @Singleton
    fun provideTextSanitizer(): TextSanitizer = AsciiFoldingTextSanitizer()

    @Provides
    @Singleton
    fun provideSecuritySessionManager(
        keyStore: SecureKeyStore
    ): SecuritySessionManager = DefaultSecuritySessionManager(keyStore)

    /**
     * Single source of projection requestIds: a Receipt is matched to its request by requestId
     * alone, so every writer on the shared ControlPoint must draw from one counter.
     */
    @Provides
    @Singleton
    fun provideRequestIdGenerator(): RequestIdGenerator = RequestIdGenerator()

    /** Single owner of the ControlPoint write path: one command in flight, mutex + FIFO queue. */
    @Provides
    @Singleton
    fun provideControlPointGateway(): ControlPointGateway = DefaultControlPointGateway()

    @Provides
    @Singleton
    fun provideInputDispatcher(
        controlPointGateway: ControlPointGateway
    ): InputDispatcher = DefaultInputDispatcher(controlPointGateway)

    /**
     * Single owner of the cluster status bar.
     *
     * The frame carries the whole bitmask, so every writer
     * — the indicator translators of `engine/indicators`, the popup degraded to an indicator — has
     * to OR its bit into one shared publisher instead of writing its own frame.
     */
    @Provides
    @Singleton
    fun provideActiveIndicatorsPublisher(
        screenProjector: ScreenProjector
    ): ActiveIndicatorsPublisher = ActiveIndicatorsPublisher(screenProjector)

    /**
     * Single owner of the mirrored state: the engine writes it and `DashboardViewModel` reads it,
     * so a second instance would be a second truth.
     */
    @Provides
    @Singleton
    fun provideProjectionMirror(): ProjectionMirror = ProjectionMirror()

    @Provides
    @Singleton
    fun provideMusicManager(
        mediaControllerAdapter: MediaControllerAdapter
    ): MusicManager = DefaultMusicManager(mediaControllerAdapter)

    @Provides
    @Singleton
    fun provideNavManager(
        notifications: NavigationNotificationBus
    ): NavigationManager = DefaultNavigationManager(notifications)

    @Provides
    @Singleton
    fun provideScreenProjector(
        requestIdGenerator: RequestIdGenerator,
        controlPointGateway: ControlPointGateway,
        policy: VehicleFramePolicy,
        holder: VehicleCapabilitiesHolder
    ): ScreenProjector = PolicyScreenProjector(
        delegate = DefaultScreenProjector(requestIdGenerator, controlPointGateway),
        policy = policy,
        holder = holder
    )

    @Provides
    fun providePageProviderFactory(
        musicManager: MusicManager,
        navManager: NavigationManager,
        callManager: CallManager,
        weatherSource: WeatherSource,
        messageSource: MessageSource,
        telecomAdapter: TelecomAdapter,
        volumeSource: VolumeSource,
        speechRecognizer: SpeechRecognizer,
        contactDirectory: ContactDirectory,
        callDialer: CallDialer,
        callMenuAccess: CallMenuAccess,
        smsSender: SmsSender,
        messageMenuAccess: MessageMenuAccess,
        navigationLauncher: NavigationLauncher,
        deviceUnlockSource: DeviceUnlockSource,
        @QuickReplies quickReplies: StateFlow<@JvmSuppressWildcards List<String>>,
        @SentLinger sentLinger: StateFlow<Duration>,
        @RootMenuEntries rootMenuEntries: StateFlow<@JvmSuppressWildcards List<RootMenuEntry>>,
        @HandlebarVolumeControl handlebarVolumeControl: StateFlow<Boolean>,
        settingsRepository: SettingsRepository,
        presentation: ClusterPresentation
    ): PageProviderFactory = DefaultPageProviderFactory(
        musicManager = musicManager,
        navManager = navManager,
        callManager = callManager,
        weatherSource = weatherSource,
        messageSource = messageSource,
        telecomAdapter = telecomAdapter,
        volumeSource = volumeSource,
        speechRecognizer = speechRecognizer,
        contactDirectory = contactDirectory,
        callDialer = callDialer,
        callMenuAccess = callMenuAccess,
        smsSender = smsSender,
        messageMenuAccess = messageMenuAccess,
        navigationLauncher = navigationLauncher,
        deviceUnlockSource = deviceUnlockSource,
        quickReplies = quickReplies,
        sentLinger = sentLinger,
        rootMenuEntries = rootMenuEntries,
        handlebarVolumeControl = handlebarVolumeControl,
        inboundCallMode = settingsRepository.settingsFlow.map { it.inboundCallMode }.distinctUntilChanged(),
        inboundMessageMode = settingsRepository.settingsFlow.map { it.inboundMessageMode }.distinctUntilChanged(),
        presentation = presentation
    )

    @Provides
    @Singleton
    fun provideBaseStateManager(
        screenProjector: ScreenProjector,
        requestIdGenerator: RequestIdGenerator,
        capabilities: VehicleCapabilitiesHolder,
        activeIndicators: ActiveIndicatorsPublisher,
        projectionMirror: ProjectionMirror,
        pageVoice: PageVoice,
        cuePlayer: CuePlayer,
        pageFactory: PageProviderFactory
    ): BaseStateManager = BaseStateManager(
        screenProjector = screenProjector,
        requestIdGenerator = requestIdGenerator,
        rootPageFactory = { pageFactory.createRootMenuPage() },
        capabilities = capabilities,
        activeIndicators = activeIndicators,
        projectionMirror = projectionMirror,
        interruptSource = pageFactory.createInterruptSource(),
        pageVoice = pageVoice,
        cuePlayer = cuePlayer
    )

    /**
     * Single owner of the live call: its status, its identity, its timestamp and its audio focus.
     *
     * One instance answers to two types on purpose. [CallManager] is what the engine watches and
     * [TelecomAdapter] is what the call page acts through: the commands that came with the
     * notification sit next to the state they act on, so "which call is live" and "how to act on
     * it" cannot disagree. Two bindings of `DefaultCallManager` would be two calls.
     */
    @Provides
    @Singleton
    fun provideDefaultCallManager(
        audioFocus: AudioFocus,
        notifications: CallNotificationBus
    ): DefaultCallManager = DefaultCallManager(notifications, audioFocus)

    @Provides
    @Singleton
    fun provideCallManager(callManager: DefaultCallManager): CallManager = callManager

    @Provides
    @Singleton
    fun provideTelecomAdapter(callManager: DefaultCallManager): TelecomAdapter = callManager

    /**
     * The status bar translators, one per capability, in the order the sources start: music,
     * navigation, call, message. The sources expose state; these decide which bit of
     * ACTIVE_INDICATORS it lights, and the one publisher keeps the write order.
     */
    @Provides
    @Singleton
    fun provideIndicatorTranslators(
        musicManager: MusicManager,
        navManager: NavigationManager,
        callManager: CallManager,
        messageSource: MessageSource,
        activeIndicators: ActiveIndicatorsPublisher
    ): List<IndicatorTranslator> = listOf(
        MusicIndicators(musicManager.currentState, activeIndicators),
        NavigationIndicator(navManager.currentInstruction, activeIndicators),
        CallIndicator(callManager.activeCall, activeIndicators),
        MessageIndicator(messageSource.currentMessage, activeIndicators)
    )

    /**
     * The engine and the five sources, engine first so the carousel exists before anything tries
     * to redraw it. The music source is followed by the auto-resume that tells it to play. Then the
     * status bar translators, after the sources they read. Then the known
     * vehicle, the single owner of saving a motorcycle the scanner paired. Owned by
     * [com.opensab.sab.session.SabSessionController]: the only class that starts and stops them.
     */
    @Provides
    @Singleton
    fun provideSessionParticipants(
        baseStateManager: BaseStateManager,
        inputDispatcher: InputDispatcher,
        musicManager: MusicManager,
        navManager: NavigationManager,
        callManager: CallManager,
        weatherSource: WeatherSource,
        messageSource: MessageSource,
        indicators: @JvmSuppressWildcards List<IndicatorTranslator>,
        vehicleRepository: VehicleRepository,
        settingsRepository: SettingsRepository,
        favoriteMediaRepository: FavoriteMediaRepository
    ): List<SessionParticipant> = listOf(
        EngineParticipant(baseStateManager, inputDispatcher),
        StartStopParticipant("music", musicManager::start, musicManager::stop),
        AutoResumeMusicParticipant(musicManager) {
            shouldAutoResume(
                enabled = settingsRepository.getSettings().autoResumeMusicOnConnect,
                favoritePackage = favoriteMediaRepository.favoritePackage.first(),
                status = musicManager.currentState.value.status
            )
        },
        StartStopParticipant("navigation", navManager::start, navManager::stop),
        StartStopParticipant("call", callManager::start, callManager::stop),
        StartStopParticipant("weather", weatherSource::start, weatherSource::stop),
        StartStopParticipant("message", messageSource::start, messageSource::stop),
        IndicatorsParticipant(indicators),
        KnownVehicleParticipant(vehicleRepository)
    )

    @Provides
    @Singleton
    fun provideSabModeWatchdog(settingsRepository: SettingsRepository): SabModeWatchdog =
        SabModeWatchdog(timeout = settingsRepository.reader(Tunable.SAB_MODE_WAIT))

    @Provides
    @Singleton
    fun provideSabModeRetry(): SabModeRetry = SabModeRetry()

    @Provides
    @Singleton
    fun provideClusterHandoverCoordinator(
        liveLinkHolder: LiveSessionLinkHolder,
        inputDispatcher: InputDispatcher,
        requestIdGenerator: RequestIdGenerator,
        sabModeWatchdog: SabModeWatchdog,
        projectionMirror: ProjectionMirror
    ): ClusterHandoverCoordinator = DefaultClusterHandoverCoordinator(
        liveLinkHolder = liveLinkHolder,
        inputDispatcher = inputDispatcher,
        requestIdGenerator = requestIdGenerator,
        sabModeWatchdog = sabModeWatchdog,
        projectionMirror = projectionMirror
    )
}

