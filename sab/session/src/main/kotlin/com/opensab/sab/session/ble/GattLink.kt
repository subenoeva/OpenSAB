package com.opensab.sab.session.ble

import com.opensab.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The connection state of one GATT link and the MTU it negotiated.
 *
 * **Owner: the transport that creates it**, one per link. Its `connect()`/`disconnect()` and its
 * callback bridge are the only callers of the transitions; the session only reads [state].
 *
 * The bring-up runs `connecting → requestingMtu → discoveringServices → ready`, and each
 * of those is accepted only from the state before it. [lost] is accepted from anywhere and is terminal
 * until the next [connecting]. A transition out of order is **not applied**: it is logged and answered
 * with `false`, never thrown, because it can arrive on the stack's callback thread. Applying a
 * `ready()` that slipped in after a `lost()` would announce `Connected` over a dead link.
 */
class GattLink {

    private val lock = Any()

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    @Volatile
    var negotiatedMtu: Int = DEFAULT_ATT_MTU
        private set

    val isConnected: Boolean get() = _state.value is BleConnectionState.Connected

    fun connecting(): Boolean = move("connecting", from = BleConnectionState.Disconnected) {
        negotiatedMtu = DEFAULT_ATT_MTU
        BleConnectionState.Connecting
    }

    fun requestingMtu(): Boolean =
        move("requestingMtu", from = BleConnectionState.Connecting) { BleConnectionState.RequestingMtu }

    /**
     * Records the MTU without moving the state. Valid while the bring-up request is out and once
     * `Connected`, where the session retries a short MTU. There it updates [negotiatedMtu] and does
     * **not** emit [state] again: a new `Connected` would wake every collector for something that is
     * not a link change, so `Connected.negotiatedMtu` stays the MTU at the moment of the handover.
     */
    fun mtuNegotiated(mtu: Int): Boolean = synchronized(lock) {
        when (val current = _state.value) {
            BleConnectionState.RequestingMtu, is BleConnectionState.Connected -> {
                negotiatedMtu = mtu
                true
            }

            else -> {
                Log.w("GATT link ignored %s in state %s", "mtuNegotiated($mtu)", current)
                false
            }
        }
    }

    fun discoveringServices(): Boolean =
        move("discoveringServices", from = BleConnectionState.RequestingMtu) { BleConnectionState.DiscoveringServices }

    /** `Connected` carries the MTU at the moment of the handover; read [negotiatedMtu] for the current one. */
    fun ready(): Boolean =
        move("ready", from = BleConnectionState.DiscoveringServices) { BleConnectionState.Connected(negotiatedMtu) }

    fun lost(): Boolean {
        synchronized(lock) { _state.value = BleConnectionState.Disconnected }
        return true
    }

    private inline fun move(
        transition: String,
        from: BleConnectionState,
        to: () -> BleConnectionState
    ): Boolean = synchronized(lock) {
        val current = _state.value
        if (current != from) {
            Log.w("GATT link ignored %s in state %s", transition, current)
            return false
        }
        _state.value = to()
        true
    }

    private companion object {
        /** ATT default before any MTU negotiation; usable payload is this minus 3. */
        const val DEFAULT_ATT_MTU = 23
    }
}
