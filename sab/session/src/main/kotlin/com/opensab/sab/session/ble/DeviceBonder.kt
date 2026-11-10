package com.opensab.sab.session.ble

import com.opensab.logging.Log
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One transition of the Android bond state machine, already parsed out of its broadcast. */
data class BondEvent(
    val macAddress: String,
    val state: BondState,
    /** Raw `UNBOND_REASON_*` code, only meaningful when [state] is [BondState.NONE]. */
    val reason: Int? = null
)

/**
 * Platform port for pairing: everything [DefaultDeviceBonder] needs from `android.bluetooth`.
 *
 * Keeping it this narrow is what lets the pairing logic run under a JVM test, the same split
 * [GattTransport] draws around the GATT layer.
 */
interface BondController {
    /** Current bond state of [macAddress] as the adapter reports it right now. */
    fun bondState(macAddress: String): BondState

    /** Asks the stack to start pairing; `false` means it declined before pairing even began. */
    fun createBond(macAddress: String): Boolean

    /**
     * Every bond transition the stack broadcasts, for every device.
     *
     * Hot on purpose: a system broadcast fires whether or not anyone is listening, and the
     * bonder has to be subscribed *before* it asks for a bond or it can miss the answer.
     */
    val bondEvents: SharedFlow<BondEvent>
}

/**
 * Guarantees a device is paired before anyone opens a GATT connection to it.
 *
 * The BTU serves `CHAR_SECURITY_STATUS` only over an encrypted link, so pairing comes first:
 * `createBond`, wait for the BONDED broadcast, and only then `connectGatt`. Connecting first ended
 * a field session in GATT_AUTH_FAIL.
 */
interface DeviceBonder {
    /** Returns once [macAddress] is paired, pairing it first if it is not. */
    suspend fun ensureBonded(macAddress: String): Result<Unit>
}

/**
 * [DeviceBonder] driven entirely by a [BondController]; holds no platform types itself.
 */
class DefaultDeviceBonder(
    private val controller: BondController,
    private val timeout: Duration = BOND_TIMEOUT
) : DeviceBonder {

    override suspend fun ensureBonded(macAddress: String): Result<Unit> = runCatchingCancellable {
        if (controller.bondState(macAddress) == BondState.BONDED) {
            Log.d("$macAddress is already bonded; skipping pairing")
            return@runCatchingCancellable
        }

        val terminal = withTimeoutOrNull(timeout) {
            controller.bondEvents
                // Pairing must not start before we are listening: the stack can report BONDED
                // before createBond() has even returned, and a broadcast nobody is registered
                // for is simply gone. onSubscription runs once the subscription is live.
                .onSubscription { startPairing(macAddress) }
                .filter { it.macAddress.equals(macAddress, ignoreCase = true) }
                .first { it.state != BondState.BONDING }
        } ?: throw BondTimedOutException(macAddress, timeout)

        if (terminal.state != BondState.BONDED) {
            throw BondFailedException(macAddress, terminal.reason)
        }
        Log.i("$macAddress is bonded; the link will come up encrypted")
    }

    /**
     * Kicks off pairing unless the stack is already on it.
     *
     * Re-reading the state here closes the window between the check above and the subscription:
     * a bond that lands in that gap leaves nothing left to wait for, so it is replayed downstream.
     */
    private suspend fun FlowCollector<BondEvent>.startPairing(macAddress: String) {
        when (controller.bondState(macAddress)) {
            BondState.BONDED -> emit(BondEvent(macAddress, BondState.BONDED))
            BondState.BONDING -> Log.d("Pairing with $macAddress is already in flight; waiting")
            BondState.NONE -> {
                Log.i("Pairing with $macAddress before opening GATT")
                if (!controller.createBond(macAddress)) {
                    throw BondFailedException(
                        macAddress = macAddress,
                        reason = null,
                        message = "The Bluetooth stack refused to start pairing with $macAddress"
                    )
                }
            }
        }
    }

    companion object {
        /**
         * Pairing takes far longer than a GATT operation: the stack holds the BONDED broadcast
         * back until service discovery finishes — 3.1 s measured on the bike — and a phone that
         * shows a consent dialog waits for the rider on top of that.
         */
        val BOND_TIMEOUT: Duration = 30.seconds
    }
}
