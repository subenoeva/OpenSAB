package com.opensab.app.service

import com.opensab.domain.autoconnect.AutoConnectDecision
import com.opensab.domain.autoconnect.AutoConnectEvaluator
import com.opensab.domain.settings.AppSettings
import com.opensab.logging.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What [BluetoothDeviceStateReceiver] does with one ACL broadcast, without the framework: read the
 * known vehicles and the settings, let [AutoConnectEvaluator] decide, and ask for the connection or
 * the disconnection. Every broadcast is also recorded as the device being linked or not, which is
 * the only writer `AclPresence` has.
 *
 * The reads, the decision and the START of a connection must fit in [budget], so the broadcast
 * keeps the process until the session has actually been asked for. Past it the broadcast lets go:
 * without a decision it asks for nothing, because the receiver finishes its pending result right
 * after and acting then would be acting from a process the system has already written off; with a
 * connection under way it leaves the START to the bootstrapper, which owns it. The limit is
 * cooperative, so it bounds suspending work, not a call that blocks its thread.
 */
class AclBroadcastHandler(
    private val knownVehicleMacs: suspend () -> Set<String>,
    private val settings: suspend () -> AppSettings,
    private val activeSessionMac: () -> String?,
    private val recordAcl: (action: String, deviceAddress: String) -> Unit,
    private val connect: (String) -> Job,
    private val disconnect: () -> Unit,
    private val budget: Duration
) {

    /**
     * The decision taken, or null when the budget ran out before there was one and nothing was asked
     * for. A connection is waited on until its START has left, within the same budget; if the budget
     * runs out first the START is left pending on the bootstrapper's own scope, not cancelled.
     */
    suspend fun handle(action: String, deviceName: String?, deviceAddress: String): AutoConnectDecision? {
        // Before the budget and whatever is decided: the retry of a failed connection reads this.
        recordAcl(action, deviceAddress)
        var decision: AutoConnectDecision? = null
        val finished = withTimeoutOrNull(budget) {
            val appSettings = settings()
            val decided = AutoConnectEvaluator.evaluate(
                action = action,
                deviceName = deviceName,
                deviceAddress = deviceAddress,
                knownVehicleMacs = knownVehicleMacs(),
                autoConnectEnabled = appSettings.autoConnect,
                activeSessionMac = activeSessionMac(),
                autoDisconnectEnabled = appSettings.autoDisconnect
            )
            decision = decided
            Log.d("BluetoothDeviceStateReceiver: action=$action, device=$deviceName ($deviceAddress) -> decision=$decided")
            when (decided) {
                is AutoConnectDecision.Connect -> {
                    Log.i("Auto-connecting to known Honda BTU: ${decided.macAddress}")
                    connect(decided.macAddress).join()
                }
                is AutoConnectDecision.Disconnect -> {
                    Log.i("Auto-disconnecting motorcycle session: ${decided.macAddress}")
                    disconnect()
                }
                AutoConnectDecision.Ignore -> Unit
            }
        }
        if (finished == null) {
            if (decision == null) {
                Log.w("BluetoothDeviceStateReceiver: action=$action, device=$deviceName ($deviceAddress) gave up after $budget without a decision")
            } else {
                Log.w("BluetoothDeviceStateReceiver: session start still pending after $budget; releasing the broadcast, the bootstrapper fallback keeps running")
            }
        }
        return decision
    }

    companion object {
        /**
         * Android expects a receiver that called `goAsync()` to finish "very quickly (under 10
         * seconds)" (developer.android.com, Broadcasts overview). Two seconds are left for what
         * runs outside the budget: `onReceive` itself, the hop to the IO dispatcher and finishing the
         * pending result. Inside it, the worst case is the reads plus the bootstrapper's 5 s claim;
         * a cold start took 1.3 s from process start to decision in the field, and a claim ~1 s.
         */
        val BROADCAST_BUDGET: Duration = 8.seconds
    }
}
