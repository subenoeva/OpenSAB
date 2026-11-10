package com.opensab.sab.session.projection

import com.opensab.logging.Log
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.projection.Receipt
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProjectionTimeoutException(message: String) : Exception(message)

class ProjectionRejectedException(
    val status: Int,
    val errorPosition: Int? = null
) : Exception("Projection frame rejected by BTU: status=$status, errorPosition=$errorPosition")

interface ScreenProjector {

    val renderState: StateFlow<RenderState>

    fun start(transport: GattTransport, modeFlow: StateFlow<DashboardMode>, scope: CoroutineScope)
    fun stop()

    suspend fun updateBaseScreen(frame: ProjectionFrame): Result<Receipt>

    /**
     * Forgets what the cluster is believed to show, so the next base frame is written even if it
     * equals the last one. For the caller that learns the earlier frames may not have been painted.
     */
    fun invalidateShownBase()

    suspend fun postPopUp(popUp: QueuedPopUp): Result<Receipt>
    suspend fun dismissActivePopUp(): Result<Unit>

    suspend fun updateActiveIndicators(mask: Int): Result<Receipt>
}

class DefaultScreenProjector(
    private val requestIdGenerator: RequestIdGenerator,
    private val controlPointGateway: ControlPointGateway
) : ScreenProjector {

    private val _renderState = MutableStateFlow<RenderState>(RenderState.Idle)
    override val renderState: StateFlow<RenderState> = _renderState.asStateFlow()

    private var transport: GattTransport? = null
    private var modeFlow: StateFlow<DashboardMode>? = null
    private var scope: CoroutineScope? = null

    private var currentBaseFrame: ProjectionFrame? = null

    /**
     * What the cluster is showing as the base screen, as written minus the requestId, and the
     * Receipt it got. Null whenever that is not known: before the first frame, after a failed write,
     * across a popup and outside SAB_MODE.
     */
    private var shownBase: ByteArray? = null
    private var shownBaseReceipt: Receipt? = null
    private var activePopUp: QueuedPopUp? = null
    private var modeObserverJob: Job? = null

    override fun start(
        transport: GattTransport,
        modeFlow: StateFlow<DashboardMode>,
        scope: CoroutineScope
    ) {
        stop()
        this.transport = transport
        this.modeFlow = modeFlow
        this.scope = scope

        Log.i("Starting ScreenProjector engine")

        modeObserverJob = scope.launch {
            controlPointGateway.start(transport, scope)
            modeFlow.collect { mode ->
                Log.d("DashboardMode changed to: $mode")
                if (mode != DashboardMode.SAB_MODE) forgetShownBase()
            }
        }
    }

    override fun stop() {
        modeObserverJob?.cancel()
        modeObserverJob = null
        controlPointGateway.clear()
        _renderState.value = RenderState.Idle
        currentBaseFrame = null
        forgetShownBase()
        activePopUp = null
        transport = null
        scope = null
    }

    override fun invalidateShownBase() = forgetShownBase()

    override suspend fun updateBaseScreen(frame: ProjectionFrame): Result<Receipt> = runCatchingCancellable {
        val currentTransport =
            transport ?: throw IllegalStateException("ScreenProjector is not started")

        val payload = frame.encode()
        val content = payload.withoutRequestId()
        val shownReceipt = shownBaseReceipt
        if (shownReceipt != null && shownBase.contentEquals(content)) {
            Log.v("Base screen frame unchanged, not writing it again (requestId=%d)", frame.requestId)
            currentBaseFrame = frame
            _renderState.value = RenderState.Projecting(baseFrame = frame, activePopUp = activePopUp)
            return@runCatchingCancellable shownReceipt
        }
        Log.v("Transmitting base screen frame [${payload.size} bytes]: ${payload.joinToString(" ") { "%02X".format(it) }}")

        forgetShownBase()
        val receipt = controlPointGateway.dispatch(
            transport = currentTransport,
            commandId = frame.commandId,
            requestId = frame.requestId,
            payload = payload
        )

        shownBase = content
        shownBaseReceipt = receipt
        currentBaseFrame = frame
        _renderState.value = RenderState.Projecting(baseFrame = frame, activePopUp = activePopUp)
        receipt
    }

    override suspend fun postPopUp(popUp: QueuedPopUp): Result<Receipt> = runCatchingCancellable {
        val currentTransport =
            transport ?: throw IllegalStateException("ScreenProjector is not started")

        val reqId = requestIdGenerator.nextId()
        // The buttons belong to the popup, not to this method: a navigation alert must not be
        // painted "hang up / answer". The tint and the background follow the side: NEGATIVE on the
        // left, POSITIVE on the right. A side the popup leaves out writes IC_NONE, and the encoder
        // then omits the whole block.
        val popUpFrame = ProjectionFrame.PopUp(
            requestId = reqId,
            bgColorId = ColorId.PURE_BLACK,
            iconId = popUp.icon,
            iconTintColorId = ColorId.PURE_WHITE,
            text = popUp.message,
            textColorId = ColorId.PURE_WHITE,
            leftActionIconId = popUp.leftActionIcon ?: IconId.IC_NONE,
            leftActionIconTintColorId = popUp.leftActionIcon?.let { ColorId.NEGATIVE },
            leftActionBgColorId = popUp.leftActionIcon?.let { ColorId.NEGATIVE },
            rightActionIconId = popUp.rightActionIcon ?: IconId.IC_NONE,
            rightActionIconTintColorId = popUp.rightActionIcon?.let { ColorId.POSITIVE },
            rightActionBgColorId = popUp.rightActionIcon?.let { ColorId.POSITIVE }
        )

        val payload = popUpFrame.encode()
        forgetShownBase()
        Log.v("Transmitting modal popup: '${popUp.message}' [${payload.size} bytes]: ${payload.joinToString(" ") { "%02X".format(it) }}")

        val receipt = controlPointGateway.dispatch(
            transport = currentTransport,
            commandId = popUpFrame.commandId,
            requestId = popUpFrame.requestId,
            payload = payload
        )

        activePopUp = popUp
        val base = currentBaseFrame ?: popUpFrame
        _renderState.value = RenderState.Projecting(baseFrame = base, activePopUp = popUp)

        // The auto-dismiss watchdog of `popUp.duration` belongs to the modal layer
        // (com.opensab.cluster.PopupManager), which owns the priority queue and the retained page.
        // A timer here would dismiss the modal behind its back.
        receipt
    }

    override suspend fun dismissActivePopUp(): Result<Unit> = runCatchingCancellable {
        activePopUp = null
        forgetShownBase()

        val currentTransport = transport
        if (currentTransport != null) {
            val dismissFrame = ProjectionFrame.DismissModal(requestId = requestIdGenerator.nextId())
            val payload = dismissFrame.encode()
            Log.v("Transmitting dismiss modal frame [${payload.size} bytes]: ${payload.joinToString(" ") { "%02X".format(it) }}")
            controlPointGateway.dispatch(
                transport = currentTransport,
                commandId = dismissFrame.commandId,
                requestId = dismissFrame.requestId,
                payload = payload
            )
        }

        val base = currentBaseFrame
        if (base != null) {
            _renderState.value = RenderState.Projecting(baseFrame = base, activePopUp = null)
        } else {
            _renderState.value = RenderState.Idle
        }
    }

    /**
     * Queues [payload] and writes it as soon as the wire is free.
     *
     * No second projection frame may be written until the one in flight has been acknowledged or its
     * watchdog has expired. Serialized by [ControlPointGateway].
     */
    override suspend fun updateActiveIndicators(mask: Int): Result<Receipt> = runCatchingCancellable {
        val currentTransport =
            transport ?: throw IllegalStateException("ScreenProjector is not started")

        val frame = ProjectionFrame.ActiveIndicators(
            requestId = requestIdGenerator.nextId(),
            indicatorMask = mask
        )
        val payload = frame.encode()
        Log.v("Transmitting active indicators mask 0x%02X [${payload.size} bytes]: ${payload.joinToString(" ") { "%02X".format(it) }}", mask)

        // The status bar lives on top of whatever page is mounted, so neither currentBaseFrame nor
        // renderState change here.
        controlPointGateway.dispatch(
            transport = currentTransport,
            commandId = frame.commandId,
            requestId = frame.requestId,
            payload = payload
        )
    }

    private fun forgetShownBase() {
        shownBase = null
        shownBaseReceipt = null
    }

    /** Byte 1 of every frame is its requestId, the one byte a repaint always changes. */
    private fun ByteArray.withoutRequestId(): ByteArray = copyOf().also { if (it.size > 1) it[1] = 0 }
}
