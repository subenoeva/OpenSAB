package com.opensab.app.di

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.opensab.app.ble.GattTransportFactory
import com.opensab.app.service.AndroidSessionLauncher
import com.opensab.app.service.AppVisibility
import com.opensab.app.service.ForegroundPromoter
import com.opensab.app.service.ForegroundServiceGrant
import com.opensab.app.service.SabConnectionService
import com.opensab.app.service.SessionBootstrapActivity
import com.opensab.app.service.SessionBootstrapper
import com.opensab.app.service.SessionLauncher
import com.opensab.app.service.wantedForegroundServiceType
import com.opensab.cluster.CueSessionAnnouncer
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.domain.audio.CuePlayer
import com.opensab.domain.audio.HeadsetPresence
import com.opensab.domain.autoconnect.AclPresence
import com.opensab.domain.settings.SettingsRepository
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.DefaultSessionPipeline
import com.opensab.sab.session.LinkOpener
import com.opensab.sab.session.LiveSessionLinkHolder
import com.opensab.sab.session.ReconnectPolicy
import com.opensab.sab.session.SabSessionController
import com.opensab.sab.session.SabSessions
import com.opensab.sab.session.SessionAnnouncer
import com.opensab.sab.session.SessionParticipant
import com.opensab.sab.session.SessionPipeline
import com.opensab.sab.session.VehiclePresence
import com.opensab.sab.session.StoredVehicleProfileResolver
import com.opensab.sab.session.assignment.DefaultAssignmentManager
import com.opensab.sab.session.ble.DeviceBonder
import com.opensab.sab.session.ble.cts.CtsServer
import com.opensab.sab.session.crypto.SecuritySessionManager
import com.opensab.sab.session.input.InputDispatcher
import com.opensab.sab.session.input.SabModeFallbacks
import com.opensab.sab.session.input.SabModeRetry
import com.opensab.sab.session.input.SabModeWatchdog
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.ControlPointGateway
import com.opensab.sab.session.projection.ScreenProjector
import com.opensab.sab.session.projection.TextSanitizer
import com.opensab.sab.session.projection.VehicleFramePolicy
import com.opensab.sab.session.storedProfileResolver
import com.opensab.sab.session.vehicle.DefaultVehicleInfoReader
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import com.opensab.sab.session.vehicle.VehicleRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The dispatcher [SabSessionController] and every [com.opensab.sab.session.SabSession] it opens run
 * on. `Dispatchers.Default` in the app; tests supply their own `TestDispatcher`
 * directly to the class under test instead of going through Hilt.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SessionDispatcher

@Module
@InstallIn(SingletonComponent::class)
object SessionModule {

    /**
     * The stall probe's threshold: a session thread more than this late for
     * its 1 s heartbeat gets logged. The harness passes `null` to disable it; the app always has one.
     */
    private val STALL_THRESHOLD = 250.milliseconds

    /** The bootstrap activity waits 1 s once resumed; past this it was blocked and the receiver starts. */
    private val BOOTSTRAP_CLAIM_TIMEOUT = 5.seconds

    /** Covers the bootstrap activity's `finish()` landing before the service's `onStartCommand`. */
    private const val VISIBILITY_GRACE_MS = 3_000L

    @Provides
    @SessionDispatcher
    fun provideSessionDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun provideLiveSessionLinkHolder(): LiveSessionLinkHolder = LiveSessionLinkHolder()

    @Provides
    @Singleton
    fun provideVehicleCapabilitiesHolder(): VehicleCapabilitiesHolder = VehicleCapabilitiesHolder()

    @Provides
    @Singleton
    fun provideVehicleFramePolicy(
        sanitizer: TextSanitizer
    ): VehicleFramePolicy = VehicleFramePolicy(sanitizer)

    /** Adapts [GattTransportFactory] so the session package never has to see it. */
    @Provides
    fun provideLinkOpener(factory: GattTransportFactory): LinkOpener = LinkOpener(factory::connect)

    @Provides
    fun provideStoredVehicleProfileResolver(
        vehicleRepository: VehicleRepository
    ): StoredVehicleProfileResolver = vehicleRepository.storedProfileResolver()

    @Provides
    @Singleton
    fun provideSessionAnnouncer(
        cuePlayer: CuePlayer,
        headsetPresence: HeadsetPresence
    ): SessionAnnouncer = CueSessionAnnouncer(cuePlayer, headsetPresence, settle = CueSessionAnnouncer.LINK_SETTLE)

    /**
     * The two developer switches as the pipeline takes them. Not a provider: a function the
     * end-to-end harness calls too, so a test reads the same two fields production reads.
     */
    fun sabModeFallbacksFrom(settingsRepository: SettingsRepository): suspend () -> SabModeFallbacks = {
        val settings = settingsRepository.getSettings()
        SabModeFallbacks(
            rerequestOnHuMode = settings.sabModeRerequestOnHuMode,
            projectWithoutGrant = settings.projectWithoutSabModeGrant
        )
    }

    @Provides
    @Singleton
    fun provideSessionPipeline(
        sessionManager: SecuritySessionManager,
        inputDispatcher: InputDispatcher,
        screenProjector: ScreenProjector,
        requestIdGenerator: RequestIdGenerator,
        capabilitiesHolder: VehicleCapabilitiesHolder,
        controlPointGateway: ControlPointGateway,
        sabModeWatchdog: SabModeWatchdog,
        sabModeRetry: SabModeRetry,
        activeIndicators: ActiveIndicatorsPublisher,
        ctsServer: CtsServer,
        liveLinkHolder: LiveSessionLinkHolder,
        announcer: SessionAnnouncer,
        storedProfileResolver: StoredVehicleProfileResolver,
        settingsRepository: SettingsRepository,
        projectionMirror: ProjectionMirror
    ): SessionPipeline = DefaultSessionPipeline(
        sessionManager = sessionManager,
        vehicleReader = DefaultVehicleInfoReader(),
        assignmentManager = DefaultAssignmentManager(),
        inputDispatcher = inputDispatcher,
        screenProjector = screenProjector,
        requestIdGenerator = requestIdGenerator,
        capabilitiesHolder = capabilitiesHolder,
        controlPointGateway = controlPointGateway,
        sabModeWatchdog = sabModeWatchdog,
        sabModeRetry = sabModeRetry,
        onSabModeNotGranted = projectionMirror::publishGrantOverdue,
        sabModeFallbacks = sabModeFallbacksFrom(settingsRepository),
        activeIndicatorsPublisher = activeIndicators,
        ctsServer = ctsServer,
        clockSyncEnabled = { settingsRepository.getSettings().ctsClockSync },
        liveLinkHolder = liveLinkHolder,
        announcer = announcer,
        storedProfileResolver = storedProfileResolver
    )

    @Provides
    @Singleton
    fun provideSabSessionController(
        bonder: DeviceBonder,
        links: LinkOpener,
        pipeline: SessionPipeline,
        participants: @JvmSuppressWildcards List<SessionParticipant>,
        @SessionDispatcher dispatcher: CoroutineDispatcher,
        presence: VehiclePresence,
    ): SabSessionController = SabSessionController(
        bonder = bonder,
        links = links,
        pipeline = pipeline,
        participants = participants,
        parent = dispatcher,
        stallThreshold = STALL_THRESHOLD,
        reconnect = ReconnectPolicy.DEFAULT,
        presence = presence,
    )

    @Provides
    @Singleton
    fun provideAclPresence(): AclPresence = AclPresence()

    /** Adapts [AclPresence] so the session package never has to see `:domain`. */
    @Provides
    fun provideVehiclePresence(aclPresence: AclPresence): VehiclePresence = VehiclePresence(aclPresence::linked)

    @Provides
    @Singleton
    fun provideSabSessions(controller: SabSessionController): SabSessions = controller

    @Provides
    @Singleton
    fun provideSessionLauncher(
        @ApplicationContext context: Context
    ): SessionLauncher = AndroidSessionLauncher(context)

    @Provides
    @Singleton
    fun provideSessionBootstrapper(
        @ApplicationContext context: Context,
        launcher: SessionLauncher,
        applicationScope: CoroutineScope
    ): SessionBootstrapper = SessionBootstrapper(
        launcher = launcher,
        overlayGranted = { Settings.canDrawOverlays(context) },
        launchActivity = { SessionBootstrapActivity.launch(context) },
        scope = applicationScope,
        claimTimeout = BOOTSTRAP_CLAIM_TIMEOUT
    )

    @Provides
    @Singleton
    fun provideAppVisibility(): AppVisibility =
        AppVisibility(clock = { SystemClock.elapsedRealtime() }, graceMs = VISIBILITY_GRACE_MS)

    @Provides
    @Singleton
    fun provideForegroundServiceGrant(): ForegroundServiceGrant = ForegroundServiceGrant(Build.VERSION.SDK_INT)

    @Provides
    @Singleton
    fun provideForegroundPromoter(
        @ApplicationContext context: Context,
        grant: ForegroundServiceGrant
    ): ForegroundPromoter = ForegroundPromoter(
        grant = grant,
        wantedType = { context.wantedForegroundServiceType() },
        requestPromotion = {
            context.startService(
                Intent(context, SabConnectionService::class.java).setAction(SabConnectionService.ACTION_PROMOTE)
            )
        }
    )
}
