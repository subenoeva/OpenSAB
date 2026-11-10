package com.opensab.app.e2e

import com.opensab.sab.protocol.assignment.AssignmentRecord
import com.opensab.sab.protocol.assignment.AssignmentStatus
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.crypto.Crc16Arc
import com.opensab.sab.protocol.crypto.SabCrypto
import com.opensab.sab.protocol.projection.CommandId
import com.opensab.sab.protocol.projection.ReceiptStatus
import com.opensab.sab.session.ble.MockGattTransport
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.UUID

/**
 * One frame as it reached the wire, split by header.
 *
 * `payload` is the whole write, header included, so a test that wants to argue about an offset can:
 * the BTU rejects with an `errorPosition` counted from byte 0 of this array.
 */
class WireFrame(
    val commandId: Int,
    val requestId: Int,
    val payload: ByteArray
) {
    /**
     * MENU and LIVE_SCREEN both travel under MYSTERY_BOX, told apart by the body type — which is
     * **not** at a fixed offset: it sits behind the header TLV, whose length the header text sets.
     *
     * Layout: `[0x01, requestId, headerBgColorId, headerTextTlv, bodyType, ...]`, and `putTlvText`
     * writes a bare `0x00` for an empty text or `len + bytes + colorId` for a present one.
     * Reading byte 2 gives the header background colour, which is how this first came back as
     * "body type 17".
     */
    val bodyType: Int?
        get() {
            if (commandId != CommandId.MYSTERY_BOX.value || payload.size < 4) return null
            val headerLength = payload[3].toInt() and 0xFF
            val offset = if (headerLength == 0) 4 else 4 + headerLength + 1
            return payload.getOrNull(offset)?.toInt()?.and(0xFF)
        }

    override fun toString(): String =
        "WireFrame(cmd=0x%02X, req=%d, %d bytes)".format(commandId, requestId, payload.size)
}

/**
 * The identity the simulated BTU serves.
 *
 * Defaults are the CB650R the field traces came from: series `0536`, a `DisplayCapability` of
 * `0x07` (indicator + pop-up + mystery box, so modals are allowed) and `SwitchType` `0x00`, which
 * is `RESERVED`, not `UNKNOWN`.
 */
class BtuProfile(
    val seriesCode: String = "0536",
    val vehicleType: Byte = 0x01,
    val displayCapability: ByteArray = byteArrayOf(0x07),
    val switchType: Byte = 0x00,
    val categoryId: Byte = 0x01,
    val destinationId: Byte = 0x12,
    val firmwareVersion: String = "1.0.0"
)

/**
 * The motorcycle, scripted: answers the handshake, acknowledges every frame and pushes the
 * notifications a real BTU pushes.
 *
 * Two limits of [MockGattTransport] this class is built on, spelled out here so nobody builds a
 * false claim on top of them:
 *
 *  1. Its notification flows are `MutableSharedFlow(replay = 1)`, so a notification emitted before
 *     anyone subscribed is **not lost, it is replayed**. "It arrived" therefore never proves "it
 *     arrived in time" — this class cannot be used to argue about ordering between a notification
 *     and a subscription.
 *  2. `onCharacteristicWrite` runs *inside* the write, under the transport's queue lock, so
 *     everything answered from there is synchronous with respect to the writer. Receipts and
 *     AssignmentResults are answered that way on purpose: it is what `ControlPointGateway` and
 *     `DefaultAssignmentManager` wait for. Everything the BTU pushes on its own — mode changes,
 *     handlebar presses — is emitted by the test, when the test wants, never from that callback.
 *     The one exception is [echoHuModeOnSabModeRequest], off by default, which reproduces a push
 *     the field logs place before the Receipt.
 *
 * What it does assert with force is the invariant no unit test covers: **one write, one Receipt,
 * and no requestId reused for the length of a session**.
 */
class BtuSimulator(
    val transport: MockGattTransport = MockGattTransport(),
    private val profile: BtuProfile = BtuProfile(),
    val aesKey: ByteArray = ByteArray(16) { 0x42 }
) {

    private val _frames = mutableListOf<WireFrame>()

    /** Every frame the cluster was asked to paint, in arrival order. */
    val frames: List<WireFrame> get() = synchronized(_frames) { _frames.toList() }

    private val _assignmentRecords = mutableListOf<ByteArray>()

    /** Assignment records, in arrival order. 27 plus END_ASSIGNMENT on a healthy session. */
    val assignmentRecords: List<ByteArray>
        get() = synchronized(_assignmentRecords) { _assignmentRecords.toList() }

    /**
     * Masks the status bar was told to light, in the order the writes landed.
     *
     * `[0x03, requestId, highByte, bitmask]`: the mask is byte 3, and byte 2 is the sign extension
     * the encoder writes for a negative mask.
     */
    val indicatorMasks: List<Int>
        get() = frames
            .filter { it.commandId == CommandId.ACTIVE_INDICATORS_CONTROL.value }
            .mapNotNull { it.payload.getOrNull(3)?.toInt()?.and(0xFF) }

    /** requestIds seen on the ControlPoint, in order. A duplicate is a defect, not a coincidence. */
    val requestIds: List<Int> get() = frames.map { it.requestId }

    /** MENU and LIVE_SCREEN only: what the rider actually sees. */
    fun baseFrames(): List<WireFrame> = frames.filter { it.commandId == CommandId.MYSTERY_BOX.value }

    /** Status the BTU answers with. Override to reject a frame, optionally at an offset. */
    var receiptStatusFor: (WireFrame) -> Int = { ReceiptStatus.SUCCESS.code }

    /** Error position sent alongside a rejecting status; null omits the 4th byte. */
    var receiptErrorPositionFor: (WireFrame) -> Int? = { null }

    /**
     * Pushes a HU_MODE notification for every REQUEST_SAB_MODE, before its Receipt.
     *
     * The opening request of a real session is followed by one, 3 to 37 ms after the write and
     * ahead of the Receipt, in every field log. Whether the BTU does it for a second request too
     * has not been measured: on, this is the worst case for whoever reacts to that HU_MODE.
     */
    var echoHuModeOnSabModeRequest: Boolean = false

    /** Commands left unanswered, so the 2000 ms watchdog fires. */
    var silentCommands: Set<Int> = emptySet()

    /** Status answered per assignment record; no test rejects one yet. */
    var assignmentStatusFor: (ByteArray) -> Int = { AssignmentStatus.SUCCESS.code }

    /** Primes identity, arms the responder and brings the link up. */
    fun boot() {
        transport.connect()
        primeIdentity()
        transport.onCharacteristicWrite = { uuid, payload -> onWrite(uuid, payload) }
    }

    /** FunctionNotification 0x09: the cluster hands the screen over. */
    suspend fun grantSabMode() = notifyFunction(FunctionId.MODE_SAB.value)

    /** FunctionNotification 0x0A: the rider took the screen back. */
    suspend fun returnToHuMode() = notifyFunction(FunctionId.MODE_HU.value)

    /** FunctionNotification 1..8: a handlebar button, decoded by the real InputDispatcher. */
    suspend fun press(functionId: FunctionId) {
        require(functionId.value in 1..8) { "$functionId is a mode notification, not a button" }
        notifyFunction(functionId.value)
    }

    // --- The BTU's own side -------------------------------------------------------------------

    private fun primeIdentity() {
        // Not yet secured, so the session manager runs the full RSA exchange instead of asking the
        // key store for a key no test has stored.
        transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x00))

        transport.setCharacteristicValue(SabGattUuids.CHAR_VEHICLE_TYPE, byteArrayOf(profile.vehicleType))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DISPLAY_CAPABILITY, profile.displayCapability)
        transport.setCharacteristicValue(SabGattUuids.CHAR_SWITCH_TYPE, byteArrayOf(profile.switchType))
        transport.setCharacteristicValue(SabGattUuids.CHAR_CATEGORY_ID, byteArrayOf(profile.categoryId))
        transport.setCharacteristicValue(SabGattUuids.CHAR_SERIES_CODE, seriesCodeOnWire(profile.seriesCode))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DESTINATION_ID, byteArrayOf(profile.destinationId))
        transport.setCharacteristicValue(
            SabGattUuids.CHAR_DFU_FIRMWARE_VERSION,
            profile.firmwareVersion.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * The series code travels as uint16 little-endian, so `"0536"` is `36 05` on the wire — reading
     * it as ASCII is what produced the phantom "Series 6".
     */
    private fun seriesCodeOnWire(hex: String): ByteArray {
        val value = hex.toInt(16)
        return byteArrayOf((value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())
    }

    private suspend fun onWrite(uuid: UUID, payload: ByteArray) {
        when (uuid) {
            SabGattUuids.CHAR_SECURITY_CONTROL_POINT -> answerSecurity(payload)
            SabGattUuids.CHAR_ASSIGNMENT_CONTROL -> answerAssignment(payload)
            SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT -> answerProjection(payload)
        }
    }

    /**
     * The two writes of the handshake, told apart by shape exactly as the BTU does: a 129-byte
     * `[0x02] + modulus` opens the RSA exchange, an 18-byte `LE16(1) + AES block` validates the key.
     */
    private fun answerSecurity(payload: ByteArray) {
        when (payload.size) {
            129 if payload[0] == 0x02.toByte() -> {
                val modulus = payload.copyOfRange(1, 129)
                val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    RSAPublicKeySpec(BigInteger(1, modulus), BigInteger.valueOf(65537))
                )
                // 110 padding + 16 AES + 2 CRC = 128 = one RSA-1024 block, not 144.
                val block = ByteArray(110) + aesKey + Crc16Arc.calculateLe(aesKey)
                transport.setCharacteristicValue(
                    SabGattUuids.CHAR_SECURITY_DATA_SOURCE,
                    SabCrypto.rsaEncrypt(publicKey, block)
                )
            }

            18 -> {
                // [status=1, incrementId LE32], encrypted, behind a 2-byte prefix.
                val answer = byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00)
                transport.setCharacteristicValue(
                    SabGattUuids.CHAR_SECURITY_DATA_SOURCE,
                    byteArrayOf(0x01, 0x00) + SabCrypto.aesEncryptPkcs7(aesKey, answer)
                )
            }
        }
    }

    /**
     * One AssignmentResult per record carrying a requestId. `END_ASSIGNMENT` (0x03) is answered with
     * nothing: the real BTU replies to it with an event, and one "Unparseable AssignmentControl
     * notification" per batch is the expected shape, not a defect.
     */
    private suspend fun answerAssignment(payload: ByteArray) {
        synchronized(_assignmentRecords) { _assignmentRecords += payload.clone() }
        if (payload.size < 3) return

        val recordType = payload[0]
        if (recordType != AssignmentRecord.RECORD_FUNCTION && recordType != AssignmentRecord.RECORD_COLOR) {
            return
        }

        transport.emitNotification(
            SabGattUuids.SERVICE_ASSIGNMENT,
            SabGattUuids.CHAR_ASSIGNMENT_CONTROL,
            byteArrayOf(recordType, payload[1], payload[2], assignmentStatusFor(payload).toByte())
        )
    }

    /** One Receipt per frame, correlated by requestId, unless the command is on [silentCommands]. */
    private suspend fun answerProjection(payload: ByteArray) {
        if (payload.isEmpty()) return
        val commandId = payload[0].toInt() and 0xFF
        val requestId = if (payload.size > 1) payload[1].toInt() and 0xFF else 0
        val frame = WireFrame(commandId, requestId, payload.clone())
        synchronized(_frames) { _frames += frame }

        if (echoHuModeOnSabModeRequest && commandId == CommandId.REQUEST_SAB_MODE.value) {
            notifyFunction(FunctionId.MODE_HU.value)
        }

        if (commandId in silentCommands) return

        val status = receiptStatusFor(frame)
        val errorPosition = receiptErrorPositionFor(frame)
        val receipt = byteArrayOf(commandId.toByte(), requestId.toByte(), status.toByte()) +
            (errorPosition?.let { byteArrayOf(it.toByte()) } ?: ByteArray(0))

        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
            receipt
        )
    }

    private suspend fun notifyFunction(code: Int) {
        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
            byteArrayOf(code.toByte())
        )
    }
}
