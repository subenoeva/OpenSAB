package com.opensab.sab.session

import com.opensab.logging.Log
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.input.HandlebarEvent
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.assignment.AssignmentManager
import com.opensab.sab.session.ble.BleConnectionState
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.cts.CtsServer
import com.opensab.sab.session.crypto.SecuritySessionManager
import com.opensab.sab.session.input.InputDispatcher
import com.opensab.sab.session.input.SabModeFallbacks
import com.opensab.sab.session.input.SabModeRetry
import com.opensab.sab.session.input.SabModeWatchdog
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.ControlPointGateway
import com.opensab.sab.session.projection.ScreenProjector
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import com.opensab.sab.session.vehicle.VehicleInfoReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class DefaultSessionPipeline(
    private val sessionManager: SecuritySessionManager,
    private val vehicleReader: VehicleInfoReader,
    private val assignmentManager: AssignmentManager,
    private val inputDispatcher: InputDispatcher,
    private val screenProjector: ScreenProjector,
    private val requestIdGenerator: RequestIdGenerator,
    private val capabilitiesHolder: VehicleCapabilitiesHolder,
    private val controlPointGateway: ControlPointGateway,
    private val sabModeWatchdog: SabModeWatchdog,
    private val sabModeRetry: SabModeRetry,
    /**
     * Told when the session-opening wait for the 0x09 runs out. The watchdog owns the wait; this is
     * only where its verdict leaves the session layer for whoever shows it to the rider.
     */
    private val onSabModeNotGranted: () -> Unit,
    /** Read once per session, at open: a change with the session live applies to the next one. */
    private val sabModeFallbacks: suspend () -> SabModeFallbacks,
    private val activeIndicatorsPublisher: ActiveIndicatorsPublisher? = null,
    private val ctsServer: CtsServer? = null,
    /** Read once per session, at open: a change with the session live applies to the next one. */
    private val clockSyncEnabled: suspend () -> Boolean,
    private val liveLinkHolder: LiveSessionLinkHolder,
    private val announcer: SessionAnnouncer? = null,
    private val storedProfileResolver: StoredVehicleProfileResolver? = null
) : SessionPipeline {

    override fun announceOpened(session: SabSession) {
        announcer?.announceOpened(session)
    }

    override fun announceClosing(session: SabSession) {
        announcer?.announceClosing(session)
        Log.i("Disconnecting OpenSAB session and stopping all adapters")
    }

    override suspend fun open(
        session: SabSession,
        transport: GattTransport,
        report: (ConnectionStep) -> Unit
    ): SabSessionState.Connected {
        val scope = session.scope
        val macAddress = session.macAddress
        Log.i("Starting OpenSAB connection pipeline for $macAddress")

        session.onClose("indicators") { activeIndicatorsPublisher?.forget() }
        session.onClose("transport") { transport.disconnect() }

        report(ConnectionStep.NEGOTIATING_MTU)
        if (transport.negotiatedMtu < SabGattUuids.TARGET_MTU) {
            transport.requestMtu(SabGattUuids.TARGET_MTU)
        }

        // CTS GATT server, for the cluster's clock synchronization
        if (ctsServer != null && clockSyncEnabled()) {
            ctsServer.start()
            session.onClose("cts") { ctsServer.stop() }
        }

        report(ConnectionStep.ESTABLISHING_SECURITY)
        val securitySession = sessionManager.establishSession(macAddress, transport).getOrThrow()

        report(ConnectionStep.READING_VEHICLE_IDENTITY)
        val stored = storedProfileResolver?.resolveStoredProfile(macAddress)
        val vehicle = vehicleReader
            .readVehicleIdentity(macAddress, transport, stored = stored)
            .getOrThrow()

        capabilitiesHolder.adopt(vehicle)
        session.onClose("capabilities") { capabilitiesHolder.reset() }

        // Arm every notification channel before anything is written to the BTU.
        report(ConnectionStep.ARMING_CHANNELS)
        controlPointGateway.start(transport, scope)
        session.onClose("gateway") { controlPointGateway.stop() }
        inputDispatcher.startListening(transport, scope)
        session.onClose("input") { inputDispatcher.stopListening() }

        report(ConnectionStep.RUNNING_ASSIGNMENT)
        assignmentManager.executeAssignment(transport, scope).getOrThrow()

        report(ConnectionStep.STARTING_ADAPTERS)
        screenProjector.start(transport, inputDispatcher.currentMode, scope)
        session.onClose("projector") { screenProjector.stop() }

        val link = LiveSessionLink(session = session, transport = transport)
        liveLinkHolder.set(link)
        session.onClose("live-link") { liveLinkHolder.clear(link) }

        // Request SAB_MODE proactively and watch for the grant
        val requestSabMode: suspend () -> Unit = {
            inputDispatcher.requestSabMode(transport, requestIdGenerator.nextId()).onFailure { error ->
                Log.w(error, "SAB_MODE request did not succeed; continuing connection pipeline")
            }
        }
        val fallbacks = sabModeFallbacks()
        if (fallbacks != SabModeFallbacks()) {
            Log.w("SAB_MODE fallbacks enabled for this session: %s", fallbacks)
        }
        sabModeRetry.requestUntilGranted(
            mode = inputDispatcher.currentMode,
            modeNotifications = inputDispatcher.events
                .filterIsInstance<HandlebarEvent.ModeChanged>()
                .map { it.mode },
            scope = scope,
            fallbacks = fallbacks,
            request = requestSabMode,
            assumeGranted = { inputDispatcher.assumeSabMode() }
        )
        val watchdogJob = sabModeWatchdog.start(inputDispatcher.currentMode, scope) {
            Log.w("Projection engine is running blind: frames are being acknowledged but the cluster is not showing them")
            onSabModeNotGranted()
        }
        link.watchdogJob.set(watchdogJob)
        session.onClose("watchdog") { link.watchdogJob.getAndSet(null)?.cancel() }

        // Periodic BLE keepalive
        scope.launch {
            while (isActive) {
                delay(5.seconds)
                if (transport.connectionState.value is BleConnectionState.Connected) {
                    if (activeIndicatorsPublisher != null) {
                        screenProjector.updateActiveIndicators(activeIndicatorsPublisher.mask)
                    }
                }
            }
        }

        scope.launch {
            transport.connectionState.collect { state ->
                if (state is BleConnectionState.Disconnected && session.connected != null) {
                    Log.i("Spontaneous BLE disconnection detected, requesting session close")
                    session.requestClose(CloseReason.LINK_LOST)
                }
            }
        }

        val connected = SabSessionState.Connected(vehicle = vehicle, session = securitySession)
        session.connected = connected
        Log.i("OpenSAB connected successfully to ${vehicle.displayName} ($macAddress)")
        return connected
    }
}
