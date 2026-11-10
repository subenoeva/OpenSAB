package com.opensab.sab.session

import com.opensab.sab.protocol.assignment.AssignmentRecord
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.crypto.Crc16Arc
import com.opensab.sab.protocol.crypto.SabCrypto
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.projection.ReceiptStatus
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.assignment.DefaultAssignmentManager
import com.opensab.sab.session.ble.MockGattTransport
import com.opensab.sab.session.ble.cts.FakeCtsServer
import com.opensab.sab.session.crypto.DefaultSecuritySessionManager
import com.opensab.sab.session.crypto.InMemorySecureKeyStore
import com.opensab.sab.session.input.DefaultInputDispatcher
import com.opensab.sab.session.input.SabModeFallbacks
import com.opensab.sab.session.input.SabModeRetry
import com.opensab.sab.session.input.SabModeWatchdog
import com.opensab.sab.session.projection.DefaultControlPointGateway
import com.opensab.sab.session.projection.DefaultScreenProjector
import com.opensab.sab.session.vehicle.DefaultVehicleInfoReader
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import kotlin.time.Duration.Companion.seconds

/**
 * Pure JVM integration test proving a full SAB session opened end to end against
 * [MockGattTransport] — RSA/AES handshake, vehicle identity, dynamic assignment, HU_MODE ->
 * SAB_MODE handover, and a projection frame acknowledged with its
 * [com.opensab.sab.protocol.projection.Receipt] — without loading `:domain`, `:cluster`, or any
 * Android framework class.
 *
 * Wires the same production classes [DefaultSessionPipeline] assembles in [SabSessionController],
 * and reads the exact bytes [MockGattTransport] receives on the wire wherever the protocol invariants
 * are load-bearing:
 *  - the RSA-1024/AES-128 handshake is driven from the modulus the phone actually generates, not a
 *    pre-baked key, and the negotiated AES key is checked against what [InMemorySecureKeyStore]
 *    persisted;
 *  - the Receipt of REQUEST_SAB_MODE only ever acknowledges the command — the dashboard stays in
 *    [DashboardMode.HU_MODE] until a separate 0x09 FunctionNotification arrives;
 *  - the PopUp frame's encoded wire bytes are asserted against a hand-built golden vector, not
 *    against [ProjectionFrame.encode] calling itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SabSessionIntegrationTest {

    private val mac = "02:00:00:00:00:AB"

    @Test
    fun fullSessionHandshakeAssignmentHandoverAndProjectionAgainstMockGatt() = runTest {
        // ---- Assemble the real graph, exactly as DefaultSessionPipeline is wired in production ----
        val keyStore = InMemorySecureKeyStore()
        val sessionManager = DefaultSecuritySessionManager(keyStore)
        val capabilitiesHolder = VehicleCapabilitiesHolder()
        val vehicleReader = DefaultVehicleInfoReader()
        val assignmentManager = DefaultAssignmentManager()
        val controlPointGateway = DefaultControlPointGateway()
        val inputDispatcher = DefaultInputDispatcher(controlPointGateway)
        val requestIdGen = RequestIdGenerator()
        val screenProjector = DefaultScreenProjector(requestIdGen, controlPointGateway)
        val watchdog = SabModeWatchdog(timeout = 10.seconds)
        val ctsServer = FakeCtsServer()
        val liveLinkHolder = LiveSessionLinkHolder()

        val pipeline = DefaultSessionPipeline(
            sessionManager = sessionManager,
            vehicleReader = vehicleReader,
            assignmentManager = assignmentManager,
            inputDispatcher = inputDispatcher,
            screenProjector = screenProjector,
            requestIdGenerator = requestIdGen,
            capabilitiesHolder = capabilitiesHolder,
            controlPointGateway = controlPointGateway,
            sabModeWatchdog = watchdog,
            sabModeRetry = SabModeRetry(),
            onSabModeNotGranted = {},
            sabModeFallbacks = { SabModeFallbacks() },
            ctsServer = ctsServer,
            clockSyncEnabled = { true },
            liveLinkHolder = liveLinkHolder
        )

        val session = SabSession(
            id = 1L,
            macAddress = mac,
            origin = SessionOrigin.RECEIVER,
            parent = backgroundScope.coroutineContext,
            stallThreshold = null,
            onCloseRequested = { _, _ -> }
        )

        // ---- 1. Pre-seed the mock BTU: security status, vehicle identity characteristics, and a
        //         live RSA/AES handshake responder wired to whatever key the phone actually sends ----
        val aesKey = ByteArray(16) { (it + 1).toByte() }
        val transport = MockGattTransport()
        transport.connect()

        transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x00))
        transport.setCharacteristicValue(SabGattUuids.CHAR_VEHICLE_TYPE, byteArrayOf(0x01))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DISPLAY_CAPABILITY, byteArrayOf(0x01, 0x02))
        transport.setCharacteristicValue(SabGattUuids.CHAR_SWITCH_TYPE, byteArrayOf(0x02))
        transport.setCharacteristicValue(SabGattUuids.CHAR_CATEGORY_ID, byteArrayOf(0x01))
        // Series 0x0536 little-endian -> parses to "0536".
        transport.setCharacteristicValue(SabGattUuids.CHAR_SERIES_CODE, byteArrayOf(0x36, 0x05))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DESTINATION_ID, byteArrayOf(0x01))

        val dataCommWrites = mutableListOf<ByteArray>()
        val assignmentWrites = mutableListOf<ByteArray>()
        transport.onCharacteristicWrite = { uuid, payload ->
            when (uuid) {
                SabGattUuids.CHAR_SECURITY_CONTROL_POINT -> {
                    if (payload.size == 129 && payload[0] == 0x02.toByte()) {
                        // The phone generated its own RSA-1024 key pair (DefaultSecuritySessionManager
                        // .performSecureExchange) and wrote [0x02] + its 128-byte public modulus. The
                        // BTU (here, this handler) encrypts the AES session key against that key.
                        val modulusBytes = payload.copyOfRange(1, 129)
                        val spec = RSAPublicKeySpec(BigInteger(1, modulusBytes), BigInteger.valueOf(65537))
                        val phonePublicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
                        val plainBlock = ByteArray(110) + aesKey + Crc16Arc.calculateLe(aesKey)
                        transport.setCharacteristicValue(
                            SabGattUuids.CHAR_SECURITY_DATA_SOURCE,
                            SabCrypto.rsaEncrypt(phonePublicKey, plainBlock)
                        )
                    } else if (payload.size == 18) {
                        // AES-encrypted validation command; answer with status=OK, incrementId=1.
                        val valPayload = byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00)
                        val encVal = SabCrypto.aesEncryptPkcs7(aesKey, valPayload)
                        transport.setCharacteristicValue(
                            SabGattUuids.CHAR_SECURITY_DATA_SOURCE,
                            byteArrayOf(0x01, 0x00) + encVal
                        )
                    }
                }

                SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT -> {
                    dataCommWrites += payload.clone()
                    // Every DataComm command (REQUEST_SAB_MODE, projection frames) gets an immediate
                    // Receipt echoed back on this same characteristic: this only ever acknowledges the
                    // command itself, never the SAB_MODE handover.
                    val cmdId = payload[0]
                    val reqId = if (payload.size > 1) payload[1] else 0
                    transport.emitNotification(
                        SabGattUuids.SERVICE_DATA_COMM,
                        SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                        byteArrayOf(cmdId, reqId, 0x00)
                    )
                }

                SabGattUuids.CHAR_ASSIGNMENT_CONTROL -> {
                    assignmentWrites += payload.clone()
                    if (payload.size >= 3) {
                        transport.emitNotification(
                            SabGattUuids.SERVICE_ASSIGNMENT,
                            SabGattUuids.CHAR_ASSIGNMENT_CONTROL,
                            byteArrayOf(payload[0], payload[1], payload[2], 0x01)
                        )
                    }
                }

                else -> Unit
            }
        }

        // ---- 2. Run the pipeline: MTU -> CTS -> RSA/AES handshake -> vehicle identity ->
        //          dynamic assignment -> adapters armed -> proactive REQUEST_SAB_MODE ----
        val steps = mutableListOf<ConnectionStep>()
        val connected = pipeline.open(session, transport) { steps += it }

        // Handshake: the negotiated AES key is exactly what the phone's own RSA exchange produced,
        // and it survived into the persistent key store.
        assertTrue("session key must be persisted after the handshake", keyStore.hasSessionKey(mac))
        assertArrayEquals(
            "persisted AES key must be the one this session negotiated over RSA",
            aesKey,
            keyStore.getSessionKey(mac)
        )
        assertEquals(mac, connected.session.macAddress)
        assertTrue("security session must be validated", connected.session.isValidated)

        // Vehicle identity: the series code the BTU reports parses correctly off the wire.
        assertEquals("0536", connected.vehicle.vehicleInfo.seriesCode)

        // Pipeline ordering and side effects.
        assertTrue(
            "connection steps must be reported in order",
            steps.indexOf(ConnectionStep.ESTABLISHING_SECURITY) < steps.indexOf(ConnectionStep.READING_VEHICLE_IDENTITY) &&
                steps.indexOf(ConnectionStep.READING_VEHICLE_IDENTITY) < steps.indexOf(ConnectionStep.RUNNING_ASSIGNMENT)
        )
        assertTrue(
            "dynamic assignment must have written function/color records to the BTU",
            assignmentWrites.size > 1
        )
        assertArrayEquals(
            "assignment batch must close with the END_ASSIGNMENT sentinel as the last write",
            AssignmentRecord.EndAssignment.encode(),
            assignmentWrites.last()
        )
        assertTrue("CTS server must be started", ctsServer.isRunning())
        assertNotNull("active live session link must be published", liveLinkHolder.current)
        assertEquals(session, liveLinkHolder.current!!.session)
        assertTrue(
            "REQUEST_SAB_MODE [0x0A] must have been written to the BTU",
            dataCommWrites.any { it[0] == 0x0A.toByte() }
        )

        // ---- 3. SAB_MODE handover: the Receipt above only acknowledged the command. The cluster is
        //          still HU_MODE until a separate 0x09 FunctionNotification arrives. ----
        assertEquals(
            "cluster must still be HU_MODE: only the Receipt has arrived so far, not the verdict",
            DashboardMode.HU_MODE,
            inputDispatcher.currentMode.value
        )

        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
            byteArrayOf(0x09)
        )
        runCurrent()

        assertEquals(
            "DashboardMode must transition to SAB_MODE on the 0x09 notification",
            DashboardMode.SAB_MODE,
            inputDispatcher.currentMode.value
        )

        // ---- 4. Project a real frame and verify the exact bytes reaching the wire, plus the Receipt
        //          parsed back from the BTU's acknowledgement. ----
        dataCommWrites.clear()
        val requestId = requestIdGen.nextId()
        val text = "OpenSAB CA-5 Pure"
        val testFrame = ProjectionFrame.PopUp(
            requestId = requestId,
            bgColorId = ColorId.PURE_BLACK,
            iconId = IconId.INCOMING_CALL,
            iconTintColorId = ColorId.PURE_WHITE,
            text = text,
            textColorId = ColorId.PURE_WHITE
        )

        val receipt = screenProjector.updateBaseScreen(testFrame).getOrThrow()

        // Hand-built golden vector, independent of
        // FrameEncoder itself: command, requestId, bgColor, icon, tint, TLV text, no action block.
        val expectedWire = byteArrayOf(0x02, requestId.toByte(), ColorId.PURE_BLACK.id.toByte(), IconId.INCOMING_CALL.id.toByte(), ColorId.PURE_WHITE.id.toByte()) +
            byteArrayOf(text.length.toByte()) +
            text.toByteArray(Charsets.UTF_8) +
            byteArrayOf(ColorId.PURE_WHITE.id.toByte())

        assertEquals(1, dataCommWrites.size)
        assertArrayEquals("the exact PopUp wire bytes must reach the BTU", expectedWire, dataCommWrites.single())

        assertEquals("Receipt requestId must match the request", requestId, receipt.requestId)
        assertTrue("Receipt must acknowledge success", receipt.isSuccess)
        assertEquals(ReceiptStatus.SUCCESS_ALT, receipt.status)

        // ---- 5. Clean closure tears down every resource the pipeline registered. ----
        session.close(CloseReason.DISCONNECT)
        runCurrent()

        assertTrue("session must be closed", session.isClosed)
        assertFalse("CTS must be stopped on close", ctsServer.isRunning())
        assertEquals(
            "DashboardMode must reset to HU_MODE on close",
            DashboardMode.HU_MODE,
            inputDispatcher.currentMode.value
        )
    }
}
