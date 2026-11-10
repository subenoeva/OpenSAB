package com.opensab.app.ble.cts

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.opensab.logging.Log
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.ble.cts.CtsData
import com.opensab.sab.session.ble.cts.CtsServer
import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation of [CtsServer] hosting Current Time Service (CTS 0x1805)
 * so the motorcycle cluster can calibrate its onboard clock.
 */
class AndroidCtsServer(
    private val context: Context
) : CtsServer {

    private var gattServer: BluetoothGattServer? = null
    private var ctsService: BluetoothGattService? = null
    private val subscribedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private var isReceiverRegistered = false

    private val timeBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d("Received time broadcast: $action")
            if (action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED) {
                notifyTimeUpdated()
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d("CTS onCharacteristicReadRequest: uuid=${characteristic.uuid} from ${device.address}")
            when (characteristic.uuid) {
                SabGattUuids.CHAR_CTS_CURRENT_TIME -> {
                    val payload = CtsData.buildCurrentTimePayload()
                    sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload)
                }
                SabGattUuids.CHAR_CTS_LOCAL_TIME_INFO -> {
                    val payload = CtsData.buildLocalTimeInfoPayload()
                    sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload)
                }
                else -> {
                    sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            Log.d("CTS onDescriptorWriteRequest: uuid=${descriptor.uuid} from ${device.address}")
            if (descriptor.uuid == SabGattUuids.DESCRIPTOR_CCCD) {
                if (value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.i("Device ${device.address} subscribed to CTS notifications")
                    subscribedDevices.add(device)
                } else if (value != null && value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    Log.i("Device ${device.address} unsubscribed from CTS notifications")
                    subscribedDevices.remove(device)
                }
                if (responseNeeded) {
                    sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } else {
                if (responseNeeded) {
                    sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d("CTS onConnectionStateChange: device=${device.address}, newState=$newState")
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                subscribedDevices.remove(device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendResponse(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ) {
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (t: Throwable) {
            Log.w(t, "Failed to send GATT server response to ${device.address}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (gattServer != null) {
            Log.d("CTS GATT server already running")
            return true
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (bluetoothManager == null) {
            Log.w("BluetoothManager not available, cannot start CTS server")
            return false
        }

        val server = try {
            bluetoothManager.openGattServer(context, gattServerCallback)
        } catch (t: Throwable) {
            Log.e(t, "Failed to open BluetoothGattServer for CTS")
            null
        }

        if (server == null) {
            Log.w("openGattServer returned null")
            return false
        }

        gattServer = server

        // Build CTS Service (0x1805)
        val service = BluetoothGattService(
            SabGattUuids.SERVICE_CTS,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Current Time Characteristic (0x2A2B - Read + Notify)
        val currentTimeChar = BluetoothGattCharacteristic(
            SabGattUuids.CHAR_CTS_CURRENT_TIME,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            SabGattUuids.DESCRIPTOR_CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        currentTimeChar.addDescriptor(cccd)
        service.addCharacteristic(currentTimeChar)

        // Local Time Information Characteristic (0x2A0F - Read)
        val localTimeChar = BluetoothGattCharacteristic(
            SabGattUuids.CHAR_CTS_LOCAL_TIME_INFO,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(localTimeChar)

        server.addService(service)
        ctsService = service

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(
                context,
                timeBroadcastReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(t, "Failed to register time broadcast receiver")
        }

        Log.i("CTS GATT server started successfully")
        return true
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(timeBroadcastReceiver)
            } catch (_: Throwable) {}
            isReceiverRegistered = false
        }

        subscribedDevices.clear()
        val server = gattServer
        if (server != null) {
            try {
                ctsService?.let { server.removeService(it) }
                server.close()
            } catch (t: Throwable) {
                Log.w(t, "Error closing CTS GATT server")
            }
            gattServer = null
            ctsService = null
        }
        Log.i("CTS GATT server stopped")
    }

    override fun isRunning(): Boolean = gattServer != null

    @SuppressLint("MissingPermission")
    override fun notifyTimeUpdated() {
        val server = gattServer ?: return
        val service = ctsService ?: return
        val char = service.getCharacteristic(SabGattUuids.CHAR_CTS_CURRENT_TIME) ?: return
        val payload = CtsData.buildCurrentTimePayload(adjustReason = 1) // Manual update

        for (device in subscribedDevices) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, char, false, payload)
                } else {
                    @Suppress("DEPRECATION")
                    char.value = payload
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, char, false)
                }
            } catch (t: Throwable) {
                Log.w(t, "Failed to notify CTS time update to ${device.address}")
            }
        }
    }
}
