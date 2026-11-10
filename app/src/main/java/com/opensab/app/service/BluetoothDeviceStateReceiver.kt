package com.opensab.app.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.opensab.app.di.ReceiverEntryPoint
import com.opensab.logging.Log
import com.opensab.sab.session.SabSessionState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver listening to system Bluetooth ACL events to automatically connect
 * and disconnect the motorcycle session.
 *
 * Declared in the manifest, so the system may start the process just to deliver one broadcast and
 * kill it as soon as the broadcast is over. The work therefore runs under `goAsync()`: the pending
 * result keeps the broadcast active until [AclBroadcastHandler] is done or its budget runs out, and
 * `finish()` sits in a `finally` so no path leaves it open.
 *
 * **Owner of each broadcast's coroutine scope**: that `onReceive` call, and nothing else. The
 * scope is created per broadcast and lives exactly as long as its pending result; a scope from the
 * graph would outlive the broadcast and hide work the system no longer waits for. The bootstrapper's
 * fallback timer stays on the application scope, with its own owner: the broadcast only waits for
 * its claim to end, so the process is not released before the START has left.
 */
class BluetoothDeviceStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return

        val device = IntentCompat.getParcelableExtra(
            intent,
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java
        )
        if (device == null) {
            Log.d("BluetoothDeviceStateReceiver: intent without EXTRA_DEVICE (action=$action), discarded")
            return
        }

        val deviceName = getDeviceNameSafely(context, device)
        val graph = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReceiverEntryPoint::class.java
        )
        val handler = AclBroadcastHandler(
            knownVehicleMacs = {
                graph.vehicleRepository().getAllVehicles().map { it.macAddress.uppercase() }.toSet()
            },
            settings = { graph.settingsRepository().getSettings() },
            activeSessionMac = {
                (graph.sabSessions().state.value as? SabSessionState.Connected)?.vehicle?.macAddress
            },
            recordAcl = graph.aclPresence()::record,
            connect = { graph.sessionBootstrapper().connect(it) },
            disconnect = { graph.sessionBootstrapper().disconnect() },
            budget = AclBroadcastHandler.BROADCAST_BUDGET
        )

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handler.handle(action, deviceName, device.address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(e, "Error evaluating Bluetooth auto-connect decision")
            } finally {
                pending.finish()
            }
        }
    }

    private fun getDeviceNameSafely(context: Context, device: BluetoothDevice): String? {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, "android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED
            ) {
                device.name
            } else {
                null
            }
        } catch (e: SecurityException) {
            Log.w(e, "SecurityException reading Bluetooth device name")
            null
        }
    }
}
