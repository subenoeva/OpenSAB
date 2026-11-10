package com.opensab.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.os.Build
import com.opensab.logging.Log
import com.opensab.sab.session.ble.BleConnectionState
import com.opensab.sab.session.ble.BleDisconnectedException
import com.opensab.sab.session.ble.CCCD_UUID
import com.opensab.sab.session.ble.GattCharacteristicNotFoundException
import com.opensab.sab.session.ble.GattLink
import com.opensab.sab.session.ble.GattOp
import com.opensab.sab.session.ble.GattOutcomes
import com.opensab.sab.session.ble.GattStatus
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.LinkEvent
import com.opensab.sab.session.ble.NotificationRouter
import com.opensab.sab.session.ble.PendingGattOperations
import com.opensab.sab.session.ble.SerializedGattQueue
import com.opensab.sab.session.ble.cccdValue
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Production [GattTransport] backed by the Android [BluetoothGatt] stack: a thin layer over the pure
 * pieces of `sab.session.ble`, keeping only what needs `android.bluetooth`.
 *
 * The Android GATT client accepts a single outstanding operation at a time: issuing a second
 * request before the previous callback fires makes the stack silently drop it. Every operation
 * therefore runs through [operationQueue], which both serializes calls and applies the
 * per-operation watchdog. This is the same one-operation-in-flight invariant the protocol layer
 * relies on to match each ControlPoint write with its Receipt by request id.
 *
 * Ownership: this instance owns [link], [pending] and [notifications], one of each per device, and
 * is the only one that writes to them; [AndroidGattTransportFactory] creates them and passes them in.
 * The [BluetoothGatt] handle is this class's own.
 *
 * Callers are responsible for holding BLUETOOTH_CONNECT (API 31+) before invoking any method, and
 * nothing here checks: a connection is only ever started from a device the scan screen found, and
 * that screen does not scan without the permission.
 */
@SuppressLint("MissingPermission")
class AndroidGattTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val operationQueue: SerializedGattQueue,
    private val link: GattLink,
    private val pending: PendingGattOperations,
    private val notifications: NotificationRouter
) : GattTransport {

    private companion object {
        val CONNECT_TIMEOUT: Duration = 15.seconds
        val DISCOVERY_TIMEOUT: Duration = 10.seconds
    }

    override val connectionState: StateFlow<BleConnectionState> = link.state
    override val negotiatedMtu: Int get() = link.negotiatedMtu

    @Volatile
    private var gatt: BluetoothGatt? = null

    // region Connection lifecycle

    /**
     * Connects, negotiates the MTU and discovers services, leaving the transport ready for use.
     *
     * A second call while a link is up or coming up is refused before touching anything: running the
     * failure path here would close the GATT of the link that is alive.
     */
    suspend fun connect(): Result<Unit> {
        if (!link.connecting()) {
            return Result.failure(IllegalStateException("GATT to ${device.address} is already connecting or connected"))
        }
        pending.reopen()
        return bringUp()
    }

    private suspend fun bringUp(): Result<Unit> = runCatchingCancellable {
        Log.i("Connecting GATT to ${device.address}")

        withTimeout(CONNECT_TIMEOUT) {
            suspendCancellableCoroutine { continuation ->
                pending.arm(GattOp.Connect, continuation)
                @Suppress("DEPRECATION")
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                continuation.invokeOnCancellation { closeGatt() }
            }
        }

        link.requestingMtu()
        requestMtu().getOrElse {
            // A short MTU is survivable; a dead link is not, and there is nothing left to continue on.
            if (it is BleDisconnectedException) throw it
            Log.w(it, "MTU negotiation failed; continuing with ${link.negotiatedMtu}")
        }

        link.discoveringServices()
        discoverServices()

        link.ready()
        Log.i("GATT ready on ${device.address} with MTU ${link.negotiatedMtu}")
    }.onFailure { cause ->
        Log.e(cause, "GATT connection to ${device.address} failed")
        closeGatt()
        link.lost()
    }

    private suspend fun discoverServices() = operationQueue.execute("Service discovery") {
        withTimeout(DISCOVERY_TIMEOUT) {
            await(GattOp.Discovery, "Service discovery") {
                GattOutcomes.legacySubmitCode(requireGatt().discoverServices())
            }
        }
    }

    override suspend fun disconnect() {
        Log.i("Disconnecting GATT from ${device.address}")
        gatt?.disconnect()
        closeGatt()
        link.lost()
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
    }

    // endregion

    // region GATT operations

    override suspend fun requestMtu(mtu: Int): Result<Int> = runCatchingCancellable {
        operationQueue.execute("MTU request of $mtu") {
            await(GattOp.Mtu, "MTU request") { GattOutcomes.legacySubmitCode(requireGatt().requestMtu(mtu)) }
        }
    }

    override suspend fun readCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): Result<ByteArray> = runCatchingCancellable {
        val characteristic = requireCharacteristic(serviceUuid, characteristicUuid)
        operationQueue.execute("Read of $characteristicUuid") {
            await(GattOp.Read, "Read of $characteristicUuid") {
                GattOutcomes.legacySubmitCode(requireGatt().readCharacteristic(characteristic))
            }
        }
    }

    override suspend fun writeCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        payload: ByteArray,
        withResponse: Boolean
    ): Result<ByteArray> = runCatchingCancellable {
        val characteristic = requireCharacteristic(serviceUuid, characteristicUuid)
        val writeType = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        operationQueue.execute("Write to $characteristicUuid") {
            await(GattOp.Write, "Write to $characteristicUuid") {
                submitWrite(characteristic, payload, writeType).also { status ->
                    // 201 is ERROR_GATT_WRITE_REQUEST_BUSY: the stack still holds an earlier operation.
                    if (status != GattOutcomes.SUBMIT_ACCEPTED) {
                        Log.w("GATT refused the write to %s: status=%d", characteristicUuid, status)
                    }
                }
            }
        }
        // Not characteristic.value: on API 33+ the write never copies the payload into it.
        payload.copyOf()
    }

    /**
     * Arms [op], submits it and suspends until the callback bridge completes it. A refused submit
     * disarms the slot and fails at once, since no callback will come.
     */
    private suspend fun <T> await(op: GattOp<T>, what: String, submit: () -> Int): T =
        suspendCancellableCoroutine { continuation ->
            pending.arm(op, continuation)
            val code = try {
                submit()
            } catch (e: Exception) {
                pending.disarm(op)
                throw e
            }
            GattOutcomes.submit(code, what).onFailure { cause ->
                pending.disarm(op)
                continuation.resumeWithException(cause)
            }
        }

    private fun submitWrite(
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int
    ): Int {
        val activeGatt = requireGatt()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(characteristic, payload, writeType)
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = payload
                GattOutcomes.legacySubmitCode(activeGatt.writeCharacteristic(characteristic))
            }
        }
    }

    override fun observeNotifications(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        onSubscriptionArmed: (suspend () -> Unit)?
    ): Flow<ByteArray> = notifications.stream(characteristicUuid)
        .onStart {
            setNotificationsEnabled(serviceUuid, characteristicUuid, enabled = true)
            Log.i("CCCD subscription armed for $characteristicUuid")
            onSubscriptionArmed?.invoke()
        }
        .onCompletion {
            if (link.isConnected) {
                // NonCancellable: a collector cancelled mid-write would release the queue while the
                // stack still holds the CCCD write, so the next write (the SAB_MODE request at the
                // end of the assignment) is refused as busy. The queue's own watchdog still bounds
                // this.
                runCatchingCancellable {
                    withContext(NonCancellable) {
                        setNotificationsEnabled(serviceUuid, characteristicUuid, enabled = false)
                    }
                }.onFailure { Log.w(it, "Could not disable notifications for $characteristicUuid") }
            }
        }

    /**
     * Enables or disables notifications locally and mirrors the change into the peer's CCCD.
     * Both halves are required: without the descriptor write the peripheral never starts sending.
     */
    private suspend fun setNotificationsEnabled(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        enabled: Boolean
    ) {
        val characteristic = requireCharacteristic(serviceUuid, characteristicUuid)
        val activeGatt = requireGatt()

        if (!activeGatt.setCharacteristicNotification(characteristic, enabled)) {
            throw BleDisconnectedException("Could not toggle notifications for $characteristicUuid")
        }

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
            ?: throw GattCharacteristicNotFoundException(
                CCCD_UUID,
                "Characteristic $characteristicUuid has no CCCD descriptor"
            )

        val supportsIndicate =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value = cccdValue(enabled, supportsIndicate)

        operationQueue.execute("CCCD write for $characteristicUuid") {
            await(GattOp.DescriptorWrite, "CCCD write for $characteristicUuid") {
                submitDescriptorWrite(activeGatt, descriptor, value).also { status ->
                    if (status != GattOutcomes.SUBMIT_ACCEPTED) {
                        Log.w("GATT refused the CCCD write for %s: status=%d", characteristicUuid, status)
                    }
                }
            }
        }
    }

    private fun submitDescriptorWrite(
        activeGatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activeGatt.writeDescriptor(descriptor, value)
    } else {
        @Suppress("DEPRECATION")
        run {
            descriptor.value = value
            GattOutcomes.legacySubmitCode(activeGatt.writeDescriptor(descriptor))
        }
    }

    // endregion

    // region Callback bridge

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (val event = GattOutcomes.link(newState, status)) {
                LinkEvent.Up -> pending.complete(GattOp.Connect, Result.success(Unit))
                is LinkEvent.ConnectFailed -> pending.complete(GattOp.Connect, Result.failure(event.cause))
                is LinkEvent.Down -> {
                    val linkStatus = GattStatus.describe(status, GattStatus.Domain.LINK)
                    Log.w("GATT disconnected from ${device.address} with status $linkStatus")
                    link.lost()
                    pending.failAll(event.cause)
                }

                LinkEvent.Ignored -> Unit
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            finish(GattOp.Discovery, GattOutcomes.operation("Service discovery", status, Unit))

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val result = GattOutcomes.operation("MTU negotiation", status, mtu)
            result.onSuccess { link.mtuNegotiated(it) }
            finish(GattOp.Mtu, result)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) = finish(GattOp.Read, GattOutcomes.operation("Read of ${characteristic.uuid}", status, value))

        @Deprecated("Framework calls this overload below API 33", level = DeprecationLevel.HIDDEN)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            finish(GattOp.Read, GattOutcomes.operation("Read of ${characteristic.uuid}", status, value))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            finish(GattOp.Write, GattOutcomes.operation("Write to ${characteristic.uuid}", status, Unit))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            notifications.publish(characteristic.uuid, value)
        }

        @Deprecated("Framework calls this overload below API 33", level = DeprecationLevel.HIDDEN)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            notifications.publish(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) = finish(
            GattOp.DescriptorWrite,
            GattOutcomes.operation("CCCD write for ${descriptor.characteristic.uuid}", status, Unit)
        )
    }

    /**
     * Hands a callback's result to whoever waits for it, logging a failure on the way.
     *
     * The log is not redundant with the exception: [PendingGattOperations.complete] drops the result
     * when nobody waits any more, which is exactly what happens when the watchdog already fired.
     * Without this line that late status would vanish without trace.
     */
    private fun <T> finish(op: GattOp<T>, result: Result<T>) {
        result.onFailure { Log.w(it.message) }
        pending.complete(op, result)
    }

    // endregion

    private fun requireGatt(): BluetoothGatt =
        gatt ?: throw BleDisconnectedException("No active GATT connection to ${device.address}")

    private fun requireCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): BluetoothGattCharacteristic {
        val service = requireGatt().getService(serviceUuid)
            ?: throw GattCharacteristicNotFoundException(
                serviceUuid,
                "GATT service not found: $serviceUuid"
            )
        return service.getCharacteristic(characteristicUuid)
            ?: throw GattCharacteristicNotFoundException(characteristicUuid)
    }
}
