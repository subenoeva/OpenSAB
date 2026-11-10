package com.opensab.sab.session.input

import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.input.HandlebarEvent
import com.opensab.sab.protocol.input.InputSource
import com.opensab.sab.session.ble.GattTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **Owner: the test that constructs it.** It implements a :sab:session contract, so it is published
 * from that module's testFixtures.
 *
 * Drives handlebar events and dashboard mode transitions from the test body.
 */
class FakeInputDispatcher : InputDispatcher {

    private val _events = MutableSharedFlow<HandlebarEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<HandlebarEvent> = _events.asSharedFlow()

    private val _currentMode = MutableStateFlow(DashboardMode.HU_MODE)
    override val currentMode: StateFlow<DashboardMode> = _currentMode.asStateFlow()

    private val _screenGrant = MutableStateFlow(ScreenGrant.NONE)
    override val screenGrant: StateFlow<ScreenGrant> = _screenGrant.asStateFlow()

    /** Like the real one, minus the per-session memory: the test decides when it is legitimate. */
    override fun assumeSabMode(): Boolean {
        if (_screenGrant.value != ScreenGrant.NONE) return false
        _screenGrant.value = ScreenGrant.ASSUMED
        return true
    }

    override suspend fun startListening(transport: GattTransport, scope: CoroutineScope) = Unit

    override fun stopListening() = Unit

    override suspend fun requestSabMode(transport: GattTransport, requestId: Int): Result<Unit> =
        Result.success(Unit)

    /** Every functionId handed to [injectMirrorPress], refusals included. */
    val injectedPresses = mutableListOf<FunctionId>()

    override suspend fun injectMirrorPress(functionId: FunctionId): Result<Unit> {
        injectedPresses += functionId
        if (_screenGrant.value == ScreenGrant.NONE) {
            return Result.failure(
                IllegalStateException("The cluster has not granted SAB_MODE")
            )
        }
        press(functionId, InputSource.MIRROR)
        return Result.success(Unit)
    }

    suspend fun enterMode(mode: DashboardMode) {
        _currentMode.value = mode
        _screenGrant.value = when {
            mode == DashboardMode.SAB_MODE -> ScreenGrant.CONFIRMED
            _screenGrant.value == ScreenGrant.ASSUMED -> ScreenGrant.ASSUMED
            else -> ScreenGrant.NONE
        }
        _events.emit(HandlebarEvent.ModeChanged(mode))
    }

    suspend fun press(
        functionId: FunctionId,
        source: InputSource = InputSource.HANDLEBAR
    ) {
        _events.emit(HandlebarEvent.ButtonPressed(functionId, source))
    }
}
