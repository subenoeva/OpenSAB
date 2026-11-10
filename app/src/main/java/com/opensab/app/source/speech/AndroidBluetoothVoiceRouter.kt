package com.opensab.app.source.speech

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.opensab.domain.speech.BluetoothVoiceRouter
import com.opensab.logging.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [BluetoothVoiceRouter] over the [BluetoothHeadset] profile: opens the helmet intercom microphone
 * with HFP voice recognition (`startVoiceRecognition`, i.e. `AT+BVRA=1`).
 *
 * Never touches [AudioManager.mode]: it stays in [AudioManager.MODE_NORMAL] to avoid call volume
 * ducking and media rerouting side effects. On API 31+ the SCO device is also set with
 * [AudioManager.setCommunicationDevice], as an audio policy backup.
 */
@SuppressLint("MissingPermission")
class AndroidBluetoothVoiceRouter(context: Context) : BluetoothVoiceRouter {

    private companion object {
        val VOICE_ROUTING_TIMEOUT: Duration = 2.seconds
    }

    private val appContext: Context = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter
    private val audioManager: AudioManager? =
        appContext.getSystemService(AudioManager::class.java)

    @Volatile
    private var headsetProxy: BluetoothHeadset? = null

    private val isConnectingProxy = AtomicBoolean(false)

    @Volatile
    override var isRoutingActive: Boolean = false
        private set

    @Volatile
    private var currentDevice: BluetoothDevice? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = proxy as? BluetoothHeadset
                isConnectingProxy.set(false)
                Log.d("BluetoothHeadset service connected")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                Log.d("BluetoothHeadset service disconnected")
                headsetProxy = null
                isConnectingProxy.set(false)
                isRoutingActive = false
                currentDevice = null
            }
        }
    }

    init {
        ensureProxy()
    }

    override val isHeadsetConnected: Boolean
        get() {
            ensureProxy()
            return try {
                headsetProxy?.connectedDevices?.isNotEmpty() == true
            } catch (e: SecurityException) {
                Log.w(e, "SecurityException checking connected headset devices")
                false
            }
        }

    override suspend fun startVoiceRouting(): Boolean {
        ensureProxy()
        val device = try {
            headsetProxy?.connectedDevices?.firstOrNull()
        } catch (e: SecurityException) {
            Log.w(e, "SecurityException retrieving connected headset device")
            null
        }

        if (device == null) {
            Log.d("No connected Bluetooth headset found for voice routing")
            return false
        }

        if (isRoutingActive || isAudioConnected(device)) {
            Log.d("Bluetooth voice routing already active or audio connected")
            setCommunicationDeviceBackup()
            currentDevice = device
            isRoutingActive = true
            return true
        }

        Log.d("Starting voice routing for headset device: %s", device.address)
        currentDevice = device

        val connected = withTimeoutOrNull(VOICE_ROUTING_TIMEOUT) {
            suspendCancellableCoroutine { continuation ->
                val isRegistered = AtomicBoolean(true)
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED) {
                            val state = intent.getIntExtra(
                                BluetoothProfile.EXTRA_STATE,
                                intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1)
                            )
                            Log.d("ACTION_AUDIO_STATE_CHANGED received: state=%d", state)
                            if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                                if (isRegistered.compareAndSet(true, false)) {
                                    try {
                                        appContext.unregisterReceiver(this)
                                    } catch (e: Exception) {
                                        Log.w(e, "Failed to unregister audio state receiver in onReceive")
                                    }
                                    if (continuation.isActive) {
                                        continuation.resume(true)
                                    }
                                }
                            }
                        }
                    }
                }

                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED
                )

                continuation.invokeOnCancellation {
                    if (isRegistered.compareAndSet(true, false)) {
                        try {
                            appContext.unregisterReceiver(receiver)
                        } catch (e: Exception) {
                            Log.w(e, "Failed to unregister audio state receiver on cancellation")
                        }
                    }
                }

                val started = try {
                    headsetProxy?.startVoiceRecognition(device) == true
                } catch (e: SecurityException) {
                    Log.w(e, "SecurityException calling startVoiceRecognition")
                    false
                }

                if (!started) {
                    Log.w("headsetProxy.startVoiceRecognition failed or returned false")
                    if (isRegistered.compareAndSet(true, false)) {
                        try {
                            appContext.unregisterReceiver(receiver)
                        } catch (e: Exception) {
                            Log.w(e, "Failed to unregister receiver on start failure")
                        }
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                    return@suspendCancellableCoroutine
                }

                if (isAudioConnected(device)) {
                    Log.d("Audio connected immediately after startVoiceRecognition")
                    if (isRegistered.compareAndSet(true, false)) {
                        try {
                            appContext.unregisterReceiver(receiver)
                        } catch (e: Exception) {
                            Log.w(e, "Failed to unregister receiver on immediate connect")
                        }
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }
                }
            }
        } ?: false

        if (connected) {
            Log.i("Bluetooth voice routing successfully connected")
            setCommunicationDeviceBackup()
            isRoutingActive = true
            return true
        } else {
            Log.w("Bluetooth voice routing timed out or failed to connect audio")
            try {
                headsetProxy?.stopVoiceRecognition(device)
            } catch (e: SecurityException) {
                Log.w(e, "SecurityException stopping voice recognition on timeout")
            }
            currentDevice = null
            isRoutingActive = false
            return false
        }
    }

    override suspend fun stopVoiceRouting() {
        Log.d("Stopping Bluetooth voice routing")
        clearCommunicationDeviceBackup()

        val device = currentDevice ?: try {
            headsetProxy?.connectedDevices?.firstOrNull()
        } catch (e: SecurityException) {
            Log.w(e, "SecurityException retrieving connected headset in stopVoiceRouting")
            null
        }

        if (device != null) {
            try {
                val stopped = headsetProxy?.stopVoiceRecognition(device)
                Log.d("stopVoiceRecognition(%s): %s", device.address, stopped)
            } catch (e: SecurityException) {
                Log.w(e, "SecurityException calling stopVoiceRecognition")
            }
        }

        currentDevice = null
        isRoutingActive = false
    }

    private fun ensureProxy() {
        if (headsetProxy == null && isConnectingProxy.compareAndSet(false, true)) {
            val success = bluetoothAdapter?.getProfileProxy(
                appContext,
                profileListener,
                BluetoothProfile.HEADSET
            ) == true
            if (!success) {
                isConnectingProxy.set(false)
            }
        }
    }

    private fun isAudioConnected(device: BluetoothDevice): Boolean = try {
        headsetProxy?.isAudioConnected(device) == true
    } catch (e: SecurityException) {
        Log.w(e, "SecurityException checking isAudioConnected")
        false
    }

    private fun setCommunicationDeviceBackup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = audioManager ?: return
            try {
                val scoDevice = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (scoDevice != null) {
                    val ok = am.setCommunicationDevice(scoDevice)
                    Log.d("setCommunicationDevice(TYPE_BLUETOOTH_SCO): %s", ok)
                } else {
                    Log.d("No TYPE_BLUETOOTH_SCO found in availableCommunicationDevices")
                }
            } catch (e: Exception) {
                Log.w(e, "Exception setting communication device backup")
            }
        }
    }

    private fun clearCommunicationDeviceBackup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = audioManager ?: return
            try {
                am.clearCommunicationDevice()
                Log.d("clearCommunicationDevice called")
            } catch (e: Exception) {
                Log.w(e, "Exception clearing communication device")
            }
        }
    }
}
