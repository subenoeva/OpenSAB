package com.opensab.app.session

import com.opensab.domain.audio.Cue
import com.opensab.domain.audio.MockCuePlayer
import com.opensab.logging.Log
import com.opensab.logging.LogSink
import com.opensab.sab.protocol.assignment.AssignmentReport
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.crypto.Crc16Arc
import com.opensab.sab.protocol.crypto.SabCrypto
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.assignment.AssignmentManager
import com.opensab.sab.session.ble.BleConnectionState
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.ble.MockGattTransport
import com.opensab.sab.session.ble.cts.FakeCtsServer
import com.opensab.sab.session.input.SabModeWatchdog
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.DefaultVehicleInfoReader
import com.opensab.sab.session.vehicle.InMemoryVehicleRepository
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.VehicleCapabilities
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import com.opensab.sab.session.vehicle.VehicleInfoReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SessionPipelineIntegrationTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun fullConnectionPipelineSucceedsAndTransitionsToConnected() = testScope.runTest {
        val writtenDataCommPayloads = mutableListOf<ByteArray>()
        val transport = createPrimedTransport { payload -> writtenDataCommPayloads.add(payload) }
        val fakeCts = FakeCtsServer()
        val rig = sessionIntegrationRig(fakeCts = fakeCts)

        val result = rig.connect(transport)

        assertTrue("Expected connection pipeline success", result.isSuccess)
        assertTrue("CtsServer must be started upon successful connection", fakeCts.isRunning())
        val connectedState = rig.state.value
        assertTrue(connectedState is SabSessionState.Connected)

        // Verify REQUEST_SAB_MODE was sent
        assertTrue(
            "REQUEST_SAB_MODE [0x0A, reqId] must be sent to ControlPoint during connection step 6",
            writtenDataCommPayloads.any { it.isNotEmpty() && it[0] == 0x0A.toByte() }
        )

        val connected = connectedState as SabSessionState.Connected
        assertEquals("0583", connected.vehicle.vehicleInfo.seriesCode)
        assertEquals(SESSION_RIG_MAC, connected.session.macAddress)

        // Disconnect
        rig.disconnect()
        assertEquals(SabSessionState.Disconnected, rig.state.value)
        assertEquals("CtsServer must be stopped upon disconnect", false, fakeCts.isRunning())
    }

    @Test
    fun `carries the stored name into a reconnection instead of re-deriving it`() = runTest {
        val repository = InMemoryVehicleRepository()
        repository.saveVehicle(
            KnownVehicle(
                macAddress = SESSION_RIG_MAC,
                displayName = "CBR650R",   // the rider resolved the 0536 ambiguity once
                vehicleInfo = vehicleInformation(seriesCode = "0536")
            )
        )
        val rig = sessionIntegrationRig(vehicleRepository = repository)
        val transport = createPrimedTransport(seriesCode = "0536")

        val connected = rig.connect(transport).getOrThrow()

        // Without this the reader would rebuild "Honda 0536" on every reconnection and the choice
        // would survive exactly until the next ignition.
        assertEquals("CBR650R", connected.vehicle.displayName)
    }

    /**
     * Seeded to SEGMENT so the adopted value differs from Permissive: cluster display is
     * rider-declared, not read from the BTU, so nothing in [createPrimedTransport] can produce it.
     */
    private fun segmentVehicleReader(): VehicleInfoReader = object : VehicleInfoReader {
        private val delegate = DefaultVehicleInfoReader()
        override suspend fun readVehicleIdentity(
            macAddress: String,
            transport: GattTransport,
            stored: com.opensab.sab.session.vehicle.VehicleProfile?
        ) = delegate.readVehicleIdentity(macAddress, transport, stored)
            .map { it.copy(clusterDisplay = ClusterDisplay.SEGMENT) }
    }

    @Test
    fun `adopts the cluster capabilities before any adapter starts`() = runTest {
        val holder = VehicleCapabilitiesHolder()
        val rig = sessionIntegrationRig(capabilitiesHolder = holder, vehicleReader = segmentVehicleReader())
        val transport = createPrimedTransport()

        rig.connect(transport).getOrThrow()

        // Adopting after step 6 would let the first frame of the session go out at full colour.
        assertTrue(holder.capabilities.value.segmentDisplay)
        assertFalse(holder.capabilities.value.supportsColor)
    }

    @Test
    fun `releases the capabilities on disconnect so the next motorcycle starts clean`() = runTest {
        val holder = VehicleCapabilitiesHolder()
        val rig = sessionIntegrationRig(capabilitiesHolder = holder, vehicleReader = segmentVehicleReader())
        val transport = createPrimedTransport()
        rig.connect(transport).getOrThrow()

        rig.disconnect()

        assertEquals(VehicleCapabilities.Permissive, holder.capabilities.value)
    }

    @Test
    fun `connection pipeline survives when SAB_MODE request receipt times out`() = runTest {
        val transport = MockGattTransport().apply { connect() }
        val aesKey = ByteArray(16) { 0x42.toByte() }
        transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x00))
        transport.setCharacteristicValue(SabGattUuids.CHAR_VEHICLE_TYPE, byteArrayOf(0x01))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DISPLAY_CAPABILITY, byteArrayOf(0x01, 0x02))
        transport.setCharacteristicValue(SabGattUuids.CHAR_SWITCH_TYPE, byteArrayOf(0x02))
        transport.setCharacteristicValue(SabGattUuids.CHAR_CATEGORY_ID, byteArrayOf(0x01))
        transport.setCharacteristicValue(SabGattUuids.CHAR_SERIES_CODE, hexToLittleEndianBytes("0583"))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DESTINATION_ID, byteArrayOf(0x02))

        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT) {
                if (payload.size == 129 && payload[0] == 0x02.toByte()) {
                    val modulusBytes = payload.copyOfRange(1, 129)
                    val spec = RSAPublicKeySpec(BigInteger(1, modulusBytes), 65537.toBigInteger())
                    val pubKey = KeyFactory.getInstance("RSA").generatePublic(spec)
                    val plainBlock = ByteArray(110) { 0x00.toByte() } + aesKey + Crc16Arc.calculateLe(aesKey)
                    transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, SabCrypto.rsaEncrypt(pubKey, plainBlock))
                } else if (payload.size == 18) {
                    val valPayload = byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00)
                    val encVal = SabCrypto.aesEncryptPkcs7(aesKey, valPayload)
                    transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, byteArrayOf(0x01, 0x00) + encVal)
                }
            } else if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                // If it's NOT SAB_MODE (0x0A), send success receipt. For SAB_MODE, DO NOT emit receipt (timeout).
                if (payload.isNotEmpty() && payload[0] != 0x0A.toByte()) {
                    val cmdId = payload[0].toInt() and 0xFF
                    val reqId = if (payload.size > 1) payload[1].toInt() and 0xFF else 0x01
                    transport.emitNotification(
                        SabGattUuids.SERVICE_DATA_COMM,
                        SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                        byteArrayOf(cmdId.toByte(), reqId.toByte(), 0x00)
                    )
                }
            }
        }

        val rig = sessionIntegrationRig()
        val result = rig.connect(transport)

        assertTrue("Connection pipeline must not abort when SAB_MODE receipt times out", result.isSuccess)
        assertTrue(rig.state.value is SabSessionState.Connected)
    }

    @Test
    fun `connection pipeline survives when SAB_MODE request receipt is rejected`() = runTest {
        val transport = MockGattTransport().apply { connect() }
        val aesKey = ByteArray(16) { 0x42.toByte() }
        transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x00))
        transport.setCharacteristicValue(SabGattUuids.CHAR_VEHICLE_TYPE, byteArrayOf(0x01))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DISPLAY_CAPABILITY, byteArrayOf(0x01, 0x02))
        transport.setCharacteristicValue(SabGattUuids.CHAR_SWITCH_TYPE, byteArrayOf(0x02))
        transport.setCharacteristicValue(SabGattUuids.CHAR_CATEGORY_ID, byteArrayOf(0x01))
        transport.setCharacteristicValue(SabGattUuids.CHAR_SERIES_CODE, hexToLittleEndianBytes("0583"))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DESTINATION_ID, byteArrayOf(0x02))

        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT) {
                if (payload.size == 129 && payload[0] == 0x02.toByte()) {
                    val modulusBytes = payload.copyOfRange(1, 129)
                    val spec = RSAPublicKeySpec(BigInteger(1, modulusBytes), 65537.toBigInteger())
                    val pubKey = KeyFactory.getInstance("RSA").generatePublic(spec)
                    val plainBlock = ByteArray(110) { 0x00.toByte() } + aesKey + Crc16Arc.calculateLe(aesKey)
                    transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, SabCrypto.rsaEncrypt(pubKey, plainBlock))
                } else if (payload.size == 18) {
                    val valPayload = byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00)
                    val encVal = SabCrypto.aesEncryptPkcs7(aesKey, valPayload)
                    transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, byteArrayOf(0x01, 0x00) + encVal)
                }
            } else if (uuid == SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT) {
                val cmdId = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0x01
                val reqId = if (payload.size > 1) payload[1].toInt() and 0xFF else 0x01
                val status = if (cmdId == 0x0A) 0x01 else 0x00 // SAB_MODE rejected with 0x01
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(cmdId.toByte(), reqId.toByte(), status.toByte())
                )
            }
        }

        val rig = sessionIntegrationRig()
        val result = rig.connect(transport)

        assertTrue("Connection pipeline must not abort when SAB_MODE receipt is rejected", result.isSuccess)
        assertTrue(rig.state.value is SabSessionState.Connected)
    }


    @Test
    fun shouldWarnWhenClusterNeverGrantsSabModeDuringTheSession() = runTest {
        val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sink = LogSink { _, _, message, _ -> recorded += message.orEmpty() }
        Log.install(sink)
        try {
            val transport = createPrimedTransport()
            val rig = sessionIntegrationRig(sabModeWatchdog = SabModeWatchdog(timeout = 50.milliseconds))

            rig.connect(transport)
            advanceTimeBy(200.milliseconds)
            runCurrent()

            assertTrue(
                "Expected the ungranted-handover warning, got: $recorded",
                recorded.any { it.contains("has NOT granted SAB_MODE") }
            )

            rig.disconnect()
        } finally {
            Log.uninstall(sink)
        }
    }

    @Test
    fun shouldArmNotificationChannelsBeforeRunningTheAssignment() = testScope.runTest {
        val order = mutableListOf<String>()
        // Delegate to the primed transport so the handshake and the identity read still succeed and
        // the pipeline actually reaches the assignment step; only record the ordering.
        val primed = createPrimedTransport()
        val transport = object : GattTransport by primed {
            override fun observeNotifications(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                onSubscriptionArmed: (suspend () -> Unit)?
            ): Flow<ByteArray> {
                order += "subscribe:$characteristicUuid"
                return primed.observeNotifications(serviceUuid, characteristicUuid, onSubscriptionArmed)
            }

            override suspend fun writeCharacteristic(
                serviceUuid: UUID,
                characteristicUuid: UUID,
                payload: ByteArray,
                withResponse: Boolean
            ): Result<ByteArray> {
                if (characteristicUuid == SabGattUuids.CHAR_ASSIGNMENT_CONTROL) {
                    order += "assignment-write"
                }
                return primed.writeCharacteristic(serviceUuid, characteristicUuid, payload, withResponse)
            }
        }

        val rig = sessionIntegrationRig()
        val result = rig.connect(transport)
        assertTrue("pipeline must reach the assignment step: ${result.exceptionOrNull()}", result.isSuccess)

        val firstAssignmentWrite = order.indexOf("assignment-write")
        assertTrue("An assignment record was written but never subscribed for", firstAssignmentWrite >= 0)

        // Primary guard: the notification channels are armed before the assignment control point is
        // touched. InputDispatcher.startListening arms the FunctionNotification channel, so its
        // subscribe must precede the first assignment write.
        val functionNotificationSubscribe = order.indexOfFirst {
            it == "subscribe:${SabGattUuids.CHAR_FUNCTION_NOTIFICATION}"
        }
        assertTrue(
            "FunctionNotification must be armed before any assignment record is written",
            functionNotificationSubscribe in 0 until firstAssignmentWrite
        )

        // The assignment manager self-subscribes to AssignmentControl before its own writes.
        val assignmentControlSubscribe = order.indexOfFirst {
            it == "subscribe:${SabGattUuids.CHAR_ASSIGNMENT_CONTROL}"
        }
        assertTrue(
            "AssignmentControl must be subscribed before any record is written",
            assignmentControlSubscribe in 0 until firstAssignmentWrite
        )

        rig.disconnect()
    }

    private fun hexToLittleEndianBytes(hex: String): ByteArray {
        val value = hex.toInt(16)
        return byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
    }

    private fun vehicleInformation(seriesCode: String = "0583") = VehicleInformation(
        vehicleType = 0x01,
        displayCapability = byteArrayOf(0x01, 0x02),
        switchType = SwitchType.TYPE_B,
        categoryId = 0x01,
        seriesCode = seriesCode,
        destinationId = DestinationId.EU
    )

    private fun createPrimedTransport(
        seriesCode: String = "0583",
        aesKey: ByteArray = ByteArray(16) { 0x42.toByte() },
        onDataCommWritten: ((ByteArray) -> Unit)? = null
    ): MockGattTransport {
        val transport = MockGattTransport()
        transport.connect()

        // Setup Mock BTU responses
        // Security
        transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x00))

        // Vehicle Info
        transport.setCharacteristicValue(SabGattUuids.CHAR_VEHICLE_TYPE, byteArrayOf(0x01))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DISPLAY_CAPABILITY, byteArrayOf(0x01, 0x02))
        transport.setCharacteristicValue(SabGattUuids.CHAR_SWITCH_TYPE, byteArrayOf(0x02))
        transport.setCharacteristicValue(SabGattUuids.CHAR_CATEGORY_ID, byteArrayOf(0x01))
        transport.setCharacteristicValue(SabGattUuids.CHAR_SERIES_CODE, hexToLittleEndianBytes(seriesCode))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DESTINATION_ID, byteArrayOf(0x02))

        // Characteristic writes: Security + Data Comm Auto-Receipts
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT) {
                if (payload.size == 129 && payload[0] == 0x02.toByte()) {
                    val modulusBytes = payload.copyOfRange(1, 129)
                    val spec = RSAPublicKeySpec(BigInteger(1, modulusBytes), 65537.toBigInteger())
                    val pubKey = KeyFactory.getInstance("RSA").generatePublic(spec)
                    val plainBlock = ByteArray(110) { 0x00.toByte() } + aesKey + Crc16Arc.calculateLe(aesKey)
                    transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, SabCrypto.rsaEncrypt(pubKey, plainBlock))
                } else if (payload.size == 18) {
                    val valPayload = byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00)
                    val encVal = SabCrypto.aesEncryptPkcs7(aesKey, valPayload)
                    transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, byteArrayOf(0x01, 0x00) + encVal)
                }
            } else if (uuid == SabGattUuids.CHAR_DATA_COMM_COMMAND) {
                onDataCommWritten?.invoke(payload.clone())
                val cmdId = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0x01
                val reqId = if (payload.size > 1) payload[1].toInt() and 0xFF else 0x01
                transport.emitNotification(
                    SabGattUuids.SERVICE_DATA_COMM,
                    SabGattUuids.CHAR_DATA_COMM_CONTROL_POINT,
                    byteArrayOf(cmdId.toByte(), reqId.toByte(), 0x00)
                )
            }
            if (uuid == SabGattUuids.CHAR_ASSIGNMENT_CONTROL && payload.size >= 3) {
                transport.emitNotification(
                    SabGattUuids.SERVICE_ASSIGNMENT,
                    SabGattUuids.CHAR_ASSIGNMENT_CONTROL,
                    byteArrayOf(payload[0], payload[1], payload[2], 0x01)
                )
            }
        }
        return transport
    }

    /**
     * The weather source lives with the session, like the other three adapters: no motorcycle, no
     * network and no GPS spent.
     */
    @Test
    fun `starts the weather source with the session`() = runTest {
        val weather = com.opensab.domain.weather.MockWeatherSource()
        val rig = sessionIntegrationRig(weatherSource = weather)
        val transport = createPrimedTransport()

        assertEquals(0, weather.startCalls)
        rig.connect(transport).getOrThrow()

        assertEquals(1, weather.startCalls)
    }

    @Test
    fun `stops the weather source when the session ends`() = runTest {
        val weather = com.opensab.domain.weather.MockWeatherSource()
        val rig = sessionIntegrationRig(weatherSource = weather)
        rig.connect(createPrimedTransport()).getOrThrow()

        rig.disconnect()

        assertEquals(
            "stopping the session must stop the weather source",
            1,
            weather.stopCalls
        )
    }

    /**
     * Register-at-acquisition: a pipeline that fails before it ever reaches the participants must
     * not stop what it never started.
     */
    @Test
    fun `does not stop the weather source when the pipeline fails before it starts`() = runTest {
        val weather = com.opensab.domain.weather.MockWeatherSource()
        val rig = sessionIntegrationRig(weatherSource = weather)
        val unprimedTransport = MockGattTransport()

        val result = rig.connect(unprimedTransport)
        assertTrue(result.isFailure)

        assertEquals(
            "the security handshake fails long before the participants start: weather never ran",
            0,
            weather.stopCalls
        )
    }

    /**
     * What the pipeline *did* acquire before failing still releases, in reverse, through the same
     * close actions the participants use — and a participant it never reached (MessageSource,
     * step 7) never starts.
     */
    @Test
    fun `a pipeline that fails after the channels are armed releases what it acquired and starts no participant`() = runTest {
        val fakeCts = FakeCtsServer()
        val capabilitiesHolder = VehicleCapabilitiesHolder()
        val message = com.opensab.domain.message.MockMessageSource()
        val failingAssignment = object : AssignmentManager {
            override suspend fun executeAssignment(
                transport: GattTransport,
                scope: kotlinx.coroutines.CoroutineScope,
                colorRegistrationNeeded: Boolean
            ): Result<AssignmentReport> = Result.failure(IllegalStateException("assignment rejected"))
        }
        val rig = sessionIntegrationRig(
            fakeCts = fakeCts,
            capabilitiesHolder = capabilitiesHolder,
            messageSource = message,
            assignmentManager = failingAssignment
        )
        val transport = createPrimedTransport()

        val result = rig.connect(transport)

        assertTrue("expected the pipeline to fail at the assignment step", result.isFailure)
        assertFalse("CtsServer must release along with everything else acquired", fakeCts.isRunning())
        assertEquals(
            "capabilities must reset along with the rest of what the pipeline acquired",
            VehicleCapabilities.Permissive,
            capabilitiesHolder.capabilities.value
        )
        assertEquals(
            "the transport must release too: it was registered before step 1",
            BleConnectionState.Disconnected,
            transport.connectionState.value
        )
        assertEquals(
            "the pipeline never reached the participants: MessageSource must never start",
            0,
            message.startCalls
        )
    }

    /**
     * The link watcher and a direct `disconnect()` both try to close, from different coroutines
     * racing on the same session; unserialized, the field log showed two "Disconnecting..." lines
     * for one ignition cut. `SabSessionController` serializes the two paths, so only the first to
     * arrive closes anything and announces it.
     */
    @Test
    fun `a link loss and a disconnect close the session once`() = runTest {
        val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sink = LogSink { _, _, message, _ -> recorded += message.orEmpty() }
        Log.install(sink)
        try {
            val player = MockCuePlayer()
            val rig = sessionIntegrationRig(cuePlayer = player)
            val transport = createPrimedTransport()
            rig.connect(transport).getOrThrow()

            transport.disconnect()
            runCurrent()
            rig.disconnect()
            runCurrent()

            assertEquals(
                "exactly one close must announce itself, whichever path won the race",
                1,
                recorded.count { it == "Disconnecting OpenSAB session and stopping all adapters" }
            )
            assertEquals(listOf(Cue.LINK_UP, Cue.LINK_DOWN), player.played)
        } finally {
            Log.uninstall(sink)
        }
    }

    /**
     * And so does the message source: without a bike in front, the bus may publish whatever it
     * likes and nobody listens or lights a bit.
     */
    @Test
    fun `starts the message source with the session`() = runTest {
        val message = com.opensab.domain.message.MockMessageSource()
        val rig = sessionIntegrationRig(messageSource = message)
        val transport = createPrimedTransport()

        assertEquals(0, message.startCalls)
        rig.connect(transport).getOrThrow()

        assertEquals(1, message.startCalls)
    }

    /**
     * The scope a source is handed is the session's, not the app's: closing the session must be
     * enough to end its coroutines, with no `stop()` call required to prove it.
     */
    @Test
    fun `the scope a source received dies with its session`() = runTest {
        var capturedScope: kotlinx.coroutines.CoroutineScope? = null
        val weather = object : com.opensab.domain.weather.WeatherSource {
            private val _currentWeather =
                kotlinx.coroutines.flow.MutableStateFlow<com.opensab.domain.weather.WeatherInfo?>(null)
            override val currentWeather: kotlinx.coroutines.flow.StateFlow<com.opensab.domain.weather.WeatherInfo?> =
                _currentWeather
            override fun refresh() {}
            override fun start(scope: kotlinx.coroutines.CoroutineScope) {
                capturedScope = scope
            }
            override fun stop() {}
        }
        val rig = sessionIntegrationRig(weatherSource = weather)
        rig.connect(createPrimedTransport()).getOrThrow()

        rig.disconnect()

        val scope = capturedScope
        assertTrue("expected WeatherSource.start to have captured a scope", scope != null)
        assertFalse(scope!!.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test
    fun `drops the unread message when the session ends`() = runTest {
        val message = com.opensab.domain.message.MockMessageSource()
        message.setMessage(com.opensab.domain.message.MessageInfo("Elena", "Are you far?"))
        val rig = sessionIntegrationRig(messageSource = message)
        rig.connect(createPrimedTransport()).getOrThrow()

        rig.disconnect()

        assertNull(
            "an unread message may not outlive the session that showed it",
            message.currentMessage.value
        )
    }

    // -------------------------------------------------------------------------------------------
    // The link
    // -------------------------------------------------------------------------------------------

    @Test
    fun `should cue the link once the session is up`() = runTest {
        val player = MockCuePlayer()
        val rig = sessionIntegrationRig(cuePlayer = player)
        var sessionStateWhenCued: SabSessionState? = null
        player.onPlay = { cue ->
            if (cue == Cue.LINK_UP) sessionStateWhenCued = rig.state.value
        }
        val transport = createPrimedTransport()

        rig.connect(transport).getOrThrow()

        assertEquals(listOf(Cue.LINK_UP), player.played)
        assertTrue(
            "the tone must follow the fact: the session must already be Connected when LINK_UP sounds",
            sessionStateWhenCued is SabSessionState.Connected
        )
    }

    @Test
    fun `should cue the unlink when a live session goes down`() = runTest {
        val player = MockCuePlayer()
        val rig = sessionIntegrationRig(cuePlayer = player)
        val transport = createPrimedTransport()
        rig.connect(transport).getOrThrow()
        player.reset()

        rig.disconnect()

        assertEquals(listOf(Cue.LINK_DOWN), player.played)
    }

    @Test
    fun `should stay quiet when the connection never came up`() = runTest {
        val player = MockCuePlayer()
        val rig = sessionIntegrationRig(cuePlayer = player)
        // No characteristic primed: the handshake read fails before any session ever exists.
        val transport = MockGattTransport().apply { connect() }

        val result = rig.connect(transport)

        assertTrue("expected the handshake to fail", result.isFailure)
        assertEquals(emptyList<Cue>(), player.played)
    }

    @Test
    fun `should not announce an unlink that never was a link`() = runTest {
        // `disconnect()` runs unconditionally — from the UI button, from the spontaneous
        // disconnection watcher and from the teardown of a handshake that never finished. Beeping
        // here would announce the end of a connection that never began.
        val player = MockCuePlayer()
        val rig = sessionIntegrationRig(cuePlayer = player)

        rig.disconnect()

        assertEquals(emptyList<Cue>(), player.played)
    }
}
