package com.opensab.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.opensab.logging.Log
import com.opensab.sab.session.ble.BondController
import com.opensab.sab.session.ble.BondEvent
import com.opensab.sab.session.ble.BondFailureReason
import com.opensab.sab.session.ble.BondState
import com.opensab.sab.session.ble.DefaultDeviceBonder
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [BondController] backed by the real Android bond state machine.
 *
 * Everything platform-specific about pairing lives here so [DefaultDeviceBonder] stays pure
 * Kotlin, the same split [AndroidGattTransport] draws around GATT. There is no JVM test for this
 * class: it is covered by `assembleDebug` and by running against the motorcycle. What can be
 * decided without a device was moved to [BondBroadcast], which does have one.
 *
 * The receiver is registered once, for the lifetime of the process, because that is how long the
 * object graph in the Hilt `SingletonComponent` lives. Registering it lazily would reopen the race the bonder
 * exists to avoid — a bond can be reported before a just-registered receiver is listening.
 *
 * Every step logs, including the ones that do nothing. On the motorcycle the stack once bonded in
 * 5.1 s while this class printed not one line, which left no way to tell a receiver that was never
 * called from one that discarded what it got.
 */
@SuppressLint("MissingPermission")
class AndroidBondController(context: Context) : BondController {

    private val appContext = context.applicationContext

    private val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _bondEvents = MutableSharedFlow<BondEvent>(
        extraBufferCapacity = BOND_EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val bondEvents: SharedFlow<BondEvent> = _bondEvents.asSharedFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = intent?.bondedDevice()
            val event = BondBroadcast.eventFrom(
                action = intent?.action,
                macAddress = device?.address,
                bondStateValue = intent.bondStateValue(),
                reasonValue = intent.reasonValue()
            )

            if (event == null) {
                Log.w(
                    "Discarding a bond broadcast: action=%s, device=%s",
                    intent?.action,
                    device?.address
                )
                return
            }

            Log.i(
                "Bond state of ${event.macAddress} is now ${event.state} " +
                    "(${BondFailureReason.describe(event.reason)})"
            )
            _bondEvents.tryEmit(event)
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BondBroadcast.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        Log.i("Listening for ${BondBroadcast.ACTION_BOND_STATE_CHANGED}")
    }

    override fun bondState(macAddress: String): BondState =
        remoteDevice(macAddress)
            ?.let { BondState.fromAndroidValue(it.bondState) }
            ?: BondState.NONE

    override fun createBond(macAddress: String): Boolean =
        remoteDevice(macAddress)?.createBond() ?: false

    private fun remoteDevice(macAddress: String): BluetoothDevice? =
        runCatchingCancellable { adapter?.getRemoteDevice(macAddress) }
            .onFailure { Log.e(it, "Cannot resolve $macAddress into a Bluetooth device") }
            .getOrNull()

    /**
     * Pulls the device out of the broadcast.
     *
     * Goes through [IntentCompat] rather than the deprecated untyped `getParcelableExtra`: since
     * Android 13 the untyped call resolves the extra without a class hint, and this app targets
     * far past that. A `null` here is survivable — [BondBroadcast] turns it into a discarded
     * broadcast that says so — but it is the one input worth not getting wrong.
     */
    private fun Intent.bondedDevice(): BluetoothDevice? =
        IntentCompat.getParcelableExtra(this, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    private fun Intent?.bondStateValue(): Int =
        this?.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) ?: BluetoothDevice.ERROR

    private fun Intent?.reasonValue(): Int =
        this?.getIntExtra(BondBroadcast.EXTRA_REASON, BondBroadcast.NO_REASON) ?: BondBroadcast.NO_REASON

    private companion object {
        /** A whole pairing run is a handful of transitions; this is slack, not a queue. */
        const val BOND_EVENT_BUFFER = 16
    }
}
