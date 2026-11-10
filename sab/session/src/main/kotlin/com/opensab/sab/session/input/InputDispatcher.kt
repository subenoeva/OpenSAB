package com.opensab.sab.session.input

import com.opensab.logging.Log
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.input.FunctionNotificationParser
import com.opensab.sab.protocol.input.HandlebarEvent
import com.opensab.sab.protocol.input.InputSource
import com.opensab.sab.protocol.projection.CommandId
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.coroutines.isCancellationOfTheCaller
import com.opensab.sab.session.coroutines.runCatchingCancellable
import com.opensab.sab.session.projection.ControlPointGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the engine may write to the screen, and on whose word.
 *
 * [CONFIRMED] and [NONE] are the cluster's SAB_MODE and HU_MODE. [ASSUMED] is the one state the BTU
 * never reported: the rider opted into projecting once the opening window closed without a 0x09.
 */
enum class ScreenGrant { NONE, ASSUMED, CONFIRMED }

interface InputDispatcher {

    val events: SharedFlow<HandlebarEvent>

    /** The mode the BTU last notified. Only a FunctionNotification changes it. */
    val currentMode: StateFlow<DashboardMode>

    /** What the engine projects by. Equal to [currentMode] unless [assumeSabMode] was called. */
    val screenGrant: StateFlow<ScreenGrant>

    /**
     * Lets the engine project although no 0x09 arrived. Refused, returning false, once the BTU has
     * granted SAB_MODE in this session: after that the screen is wherever the rider left it.
     *
     * A HU_MODE notification does not undo it, because the assumption is made against a BTU that is
     * already saying HU_MODE. It ends with a 0x09, which confirms it, or with the session.
     */
    fun assumeSabMode(): Boolean

    suspend fun startListening(transport: GattTransport, scope: CoroutineScope)
    fun stopListening()

    /**
     * Writes [0x0A, requestId]. Nothing is discarded while it is in flight: the HU_MODE the BTU pushes
     * on subscription arrives then, and it is the cluster's current mode, delivered as state.
     */
    suspend fun requestSabMode(transport: GattTransport, requestId: Int): Result<Unit>

    /**
     * Emits a synthetic press from the in-app mirror. Refused while [screenGrant] is
     * [ScreenGrant.NONE].
     *
     * The press enters through the same [events] flow a handlebar notification does, so the engine
     * cannot tell the two apart — which is what makes divergence between the app and the cluster
     * impossible by construction rather than by discipline.
     */
    suspend fun injectMirrorPress(functionId: FunctionId): Result<Unit>
}

class DefaultInputDispatcher(
    private val controlPointGateway: ControlPointGateway
) : InputDispatcher {

    private val _events = MutableSharedFlow<HandlebarEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<HandlebarEvent> = _events.asSharedFlow()

    private val _currentMode = MutableStateFlow(DashboardMode.HU_MODE)
    override val currentMode: StateFlow<DashboardMode> = _currentMode.asStateFlow()

    /**
     * Sole owner of the grant. Written from three places, all under [grantLock]: the notification
     * collector, [assumeSabMode] and [stopListening].
     */
    private val _screenGrant = MutableStateFlow(ScreenGrant.NONE)
    override val screenGrant: StateFlow<ScreenGrant> = _screenGrant.asStateFlow()

    private val grantLock = Any()

    /** Whether a 0x09 arrived in this session. Guarded by [grantLock]. */
    private var grantedThisSession = false

    private var listeningJob: Job? = null

    override suspend fun startListening(transport: GattTransport, scope: CoroutineScope) {
        stopListening()
        Log.i("Starting handlebar input listening loop on FunctionNotification")
        controlPointGateway.start(transport, scope)

        val armed = CompletableDeferred<Unit>()
        val job = scope.launch {
            try {
                transport.observeNotifications(
                    serviceUuid = SabGattUuids.SERVICE_DATA_COMM,
                    characteristicUuid = SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
                    onSubscriptionArmed = { armed.complete(Unit) }
                ).collect { payload ->
                    val event = FunctionNotificationParser.parse(payload)
                    if (event != null) {
                        // The event's toString() is a data class dump; this line is the one meant to be counted.
                        if (event is HandlebarEvent.ButtonPressed) Log.i("Handlebar press %s", event.functionId)
                        Log.d("Received handlebar event: $event")
                        if (event is HandlebarEvent.ModeChanged) {
                            _currentMode.value = event.mode
                            onModeNotified(event.mode)
                        }
                        _events.tryEmit(event)
                    } else {
                        Log.w("Unrecognized notification payload: %s", payload.joinToString(" ") { "%02X".format(it) })
                    }
                }
            } catch (e: Throwable) {
                if (e.isCancellationOfTheCaller()) {
                    Log.d("FunctionNotification observation cancelled")
                    throw e
                }
                Log.w(e, "FunctionNotification observation failed")
                if (!armed.isCompleted) {
                    armed.completeExceptionally(e)
                }
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null && !armed.isCompleted) {
                armed.completeExceptionally(cause)
            }
        }
        listeningJob = job
        try {
            withTimeout(2.seconds) { armed.await() }
        } catch (e: Throwable) {
            job.cancel()
            listeningJob = null
            throw e
        }
    }

    override fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        // Session over: this dispatcher is a @Singleton, so a retained SAB_MODE would leak into the
        // next connection and make SabModeWatchdog never fire against a cluster that granted nothing
        // this session. No screen ownership without a live session.
        _currentMode.value = DashboardMode.HU_MODE
        synchronized(grantLock) {
            grantedThisSession = false
            _screenGrant.value = ScreenGrant.NONE
        }
    }

    private fun onModeNotified(mode: DashboardMode) = synchronized(grantLock) {
        when (mode) {
            DashboardMode.SAB_MODE -> {
                if (_screenGrant.value == ScreenGrant.ASSUMED) {
                    Log.i("0x09 arrived after projecting without it: the assumed grant is now confirmed")
                }
                grantedThisSession = true
                _screenGrant.value = ScreenGrant.CONFIRMED
            }

            DashboardMode.HU_MODE -> {
                if (_screenGrant.value == ScreenGrant.ASSUMED) {
                    Log.i("HU_MODE notified while projecting without a 0x09: keeping the assumed grant")
                } else {
                    _screenGrant.value = ScreenGrant.NONE
                }
            }
        }
    }

    override fun assumeSabMode(): Boolean = synchronized(grantLock) {
        if (grantedThisSession || _screenGrant.value != ScreenGrant.NONE) {
            Log.i("Not assuming SAB_MODE: the BTU already granted it in this session")
            return false
        }
        Log.w("Assuming SAB_MODE without a 0x09: frames will be written to a cluster that reported HU_MODE")
        _screenGrant.value = ScreenGrant.ASSUMED
        true
    }

    override suspend fun requestSabMode(transport: GattTransport, requestId: Int): Result<Unit> = runCatchingCancellable {
        Log.i("Proactively requesting SAB_MODE from BTU [requestId=$requestId]")
        val payload = byteArrayOf(0x0A.toByte(), (requestId and 0xFF).toByte())
        controlPointGateway.dispatch(
            transport = transport,
            commandId = CommandId.REQUEST_SAB_MODE,
            requestId = requestId,
            payload = payload
        )
        // The Receipt acknowledges the command, never the handover. The cluster keeps the
        // screen until it says otherwise with a MODE_SAB (0x09) FunctionNotification, the single
        // source of truth for the mode.
        Log.i(
            "BTU acknowledged REQUEST_SAB_MODE [requestId=%d]; still HU_MODE until a 0x09 notification arrives",
            requestId
        )
    }

    /**
     * The third of three guards for the same case: the UI disables the zones and
     * [com.opensab.cluster.BaseStateManager] already drops presses outside SAB_MODE. It stays
     * because the failure it prevents — a frame written while the cluster paints the native
     * tachometer — is not observable from the app.
     */
    override suspend fun injectMirrorPress(functionId: FunctionId): Result<Unit> {
        if (_screenGrant.value == ScreenGrant.NONE) {
            Log.w("Refusing mirror press %s: the cluster has not granted SAB_MODE", functionId)
            return Result.failure(
                IllegalStateException(
                    "Cannot inject $functionId: the cluster has not granted SAB_MODE"
                )
            )
        }
        Log.d("Injecting mirror press %s", functionId)
        _events.emit(HandlebarEvent.ButtonPressed(functionId, InputSource.MIRROR))
        return Result.success(Unit)
    }
}

