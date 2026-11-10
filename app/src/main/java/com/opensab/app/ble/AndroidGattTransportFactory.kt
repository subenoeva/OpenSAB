package com.opensab.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.opensab.logging.Log
import com.opensab.sab.session.ble.BleDisconnectedException
import com.opensab.sab.session.ble.GattLink
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.NotificationRouter
import com.opensab.sab.session.ble.PendingGattOperations
import com.opensab.sab.session.ble.SerializedGattQueue
import com.opensab.sab.session.coroutines.runCatchingCancellable

@SuppressLint("MissingPermission")
class AndroidGattTransportFactory(context: Context) : GattTransportFactory {

    private val appContext = context.applicationContext

    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    override suspend fun connect(macAddress: String): Result<GattTransport> = runCatchingCancellable {
        val adapter = bluetoothManager?.adapter
            ?: throw BleDisconnectedException("This device has no Bluetooth adapter")
        if (!adapter.isEnabled) {
            throw BleDisconnectedException("Bluetooth is turned off")
        }

        val device = adapter.getRemoteDevice(macAddress)
        Log.i("Building GATT transport for $macAddress")

        AndroidGattTransport(
            context = appContext,
            device = device,
            operationQueue = SerializedGattQueue(),
            link = GattLink(),
            pending = PendingGattOperations(),
            notifications = NotificationRouter()
        ).also { transport ->
            transport.connect().getOrThrow()
        }
    }
}
