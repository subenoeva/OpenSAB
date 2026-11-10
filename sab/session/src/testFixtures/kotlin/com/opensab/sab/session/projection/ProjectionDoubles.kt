package com.opensab.sab.session.projection

import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.projection.Receipt
import com.opensab.sab.protocol.projection.ReceiptStatus
import com.opensab.sab.session.ble.GattTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **Owner: the test that constructs it.** It implements a :sab:session contract, so it is published
 * from that module's testFixtures.
 *
 * Captures every frame and popup handed to the projector instead of writing them to a transport.
 */
class RecordingScreenProjector : ScreenProjector {

    val frames = mutableListOf<ProjectionFrame>()
    val popUps = mutableListOf<QueuedPopUp>()
    var dismissCount = 0
        private set

    private val _renderState = MutableStateFlow<RenderState>(RenderState.Idle)
    override val renderState: StateFlow<RenderState> = _renderState.asStateFlow()

    override fun start(
        transport: GattTransport,
        modeFlow: StateFlow<DashboardMode>,
        scope: CoroutineScope
    ) = Unit

    override fun stop() = Unit

    /**
     * Runs just before a frame is recorded, so a test can observe the state of the world at the
     * instant of the write. It is how the ordering of mirror publication against the write is
     * asserted without reaching into the engine's internals.
     */
    var onBeforeWrite: (() -> Unit)? = null

    /** When set, every base screen write fails with it instead of acknowledging the frame. */
    var failWith: Throwable? = null

    override suspend fun updateBaseScreen(frame: ProjectionFrame): Result<Receipt> {
        onBeforeWrite?.invoke()
        frames += frame
        failWith?.let { return Result.failure(it) }
        return Result.success(Receipt(frame.commandId, frame.requestId, ReceiptStatus.SUCCESS))
    }

    /** How many frames had been recorded each time the shown base was invalidated. */
    val invalidatedAt = mutableListOf<Int>()

    override fun invalidateShownBase() {
        invalidatedAt += frames.size
    }

    override suspend fun postPopUp(popUp: QueuedPopUp): Result<Receipt> {
        popUps += popUp
        return Result.success(Receipt(com.opensab.sab.protocol.projection.CommandId.POP_UP, 0, ReceiptStatus.SUCCESS))
    }

    override suspend fun dismissActivePopUp(): Result<Unit> {
        dismissCount++
        return Result.success(Unit)
    }

    val indicatorMasks = mutableListOf<Int>()

    override suspend fun updateActiveIndicators(mask: Int): Result<Receipt> {
        indicatorMasks += mask
        return Result.success(
            Receipt(
                com.opensab.sab.protocol.projection.CommandId.ACTIVE_INDICATORS_CONTROL,
                0,
                ReceiptStatus.SUCCESS
            )
        )
    }
}
