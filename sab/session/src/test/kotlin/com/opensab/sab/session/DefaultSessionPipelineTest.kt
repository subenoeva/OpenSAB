package com.opensab.sab.session

import com.opensab.logging.Log
import com.opensab.logging.LogSink
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.crypto.Crc16Arc
import com.opensab.sab.protocol.crypto.SabCrypto
import com.opensab.sab.protocol.input.DashboardMode
import com.opensab.sab.protocol.projection.RequestIdGenerator
import com.opensab.sab.session.assignment.DefaultAssignmentManager
import com.opensab.sab.session.ble.BleConnectionState
import com.opensab.sab.session.ble.MockGattTransport
import com.opensab.sab.session.ble.cts.CtsServer
import com.opensab.sab.session.ble.cts.FakeCtsServer
import com.opensab.sab.session.crypto.DefaultSecuritySessionManager
import com.opensab.sab.session.crypto.InMemorySecureKeyStore
import com.opensab.sab.session.input.DefaultInputDispatcher
import com.opensab.sab.session.input.SabModeFallbacks
import com.opensab.sab.session.input.SabModeRetry
import com.opensab.sab.session.input.ScreenGrant
import com.opensab.sab.session.input.SabModeWatchdog
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.DefaultControlPointGateway
import com.opensab.sab.session.projection.DefaultScreenProjector
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.DefaultVehicleInfoReader
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import com.opensab.sab.session.vehicle.VehicleInfoReader
import com.opensab.sab.session.vehicle.VehicleProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [DefaultSessionPipeline] proven against [MockGattTransport]: the full pipeline from MTU to
 * Connected, the canonical log line, link publication, watchdog, keepalive and spontaneous
 * disconnection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSessionPipelineTest {

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private fun hexToLittleEndianBytes(hex: String): ByteArray {
        val value = hex.toInt(16)
        return byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
    }

    private fun createPrimedTransport(
        seriesCode: String = "0583",
        aesKey: ByteArray = ByteArray(16) { 0x42.toByte() },
        onDataCommWritten: ((ByteArray) -> Unit)? = null
    ): MockGattTransport {
        val transport = MockGattTransport()
        transport.connect()

        transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x00))
        transport.setCharacteristicValue(SabGattUuids.CHAR_VEHICLE_TYPE, byteArrayOf(0x01))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DISPLAY_CAPABILITY, byteArrayOf(0x01, 0x02))
        transport.setCharacteristicValue(SabGattUuids.CHAR_SWITCH_TYPE, byteArrayOf(0x02))
        transport.setCharacteristicValue(SabGattUuids.CHAR_CATEGORY_ID, byteArrayOf(0x01))
        transport.setCharacteristicValue(SabGattUuids.CHAR_SERIES_CODE, hexToLittleEndianBytes(seriesCode))
        transport.setCharacteristicValue(SabGattUuids.CHAR_DESTINATION_ID, byteArrayOf(0x02))

        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT) {
                if (payload.size == 129 && payload[0] == 0x02.toByte()) {
                    val modulusBytes = payload.copyOfRange(1, 129)
                    val spec = RSAPublicKeySpec(BigInteger(1, modulusBytes), 65537.toBigInteger())
                    val pubKey = KeyFactory.getInstance("RSA").generatePublic(spec)
                    val plainBlock = ByteArray(110) { 0x00 } + aesKey + Crc16Arc.calculateLe(aesKey)
                    transport.setCharacteristicValue(
                        SabGattUuids.CHAR_SECURITY_DATA_SOURCE,
                        SabCrypto.rsaEncrypt(pubKey, plainBlock)
                    )
                } else if (payload.size == 18) {
                    val valPayload = byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00)
                    val encVal = SabCrypto.aesEncryptPkcs7(aesKey, valPayload)
                    transport.setCharacteristicValue(
                        SabGattUuids.CHAR_SECURITY_DATA_SOURCE,
                        byteArrayOf(0x01, 0x00) + encVal
                    )
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

    private class RecordedLog : LogSink, AutoCloseable {
        val lines: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        override fun log(priority: Int, t: Throwable?, message: String?, args: Array<out Any?>) {
            lines += if (args.isEmpty()) message.orEmpty() else String.format(message.orEmpty(), *args)
        }

        override fun close() = Log.uninstall(this)
    }

    private fun recordLog(): RecordedLog = RecordedLog().also { Log.install(it) }

    private class FakeAnnouncer : SessionAnnouncer {
        val opened = mutableListOf<Long>()
        val closings = mutableListOf<Long>()

        override fun announceOpened(session: SabSession) {
            opened += session.id
        }

        override fun announceClosing(session: SabSession) {
            closings += session.id
        }
    }

    /** The dispatcher of the last [pipeline] built, for the tests that read the grant it holds. */
    private lateinit var builtDispatcher: DefaultInputDispatcher

    private fun pipeline(
        fakeCts: CtsServer? = FakeCtsServer(),
        capabilitiesHolder: VehicleCapabilitiesHolder = VehicleCapabilitiesHolder(),
        sabModeWatchdog: SabModeWatchdog = SabModeWatchdog(timeout = 10.seconds),
        sabModeRetry: SabModeRetry = SabModeRetry(),
        sabModeFallbacks: SabModeFallbacks = SabModeFallbacks(),
        activeIndicatorsPublisher: ActiveIndicatorsPublisher? = null,
        announcer: SessionAnnouncer? = null,
        storedProfileResolver: StoredVehicleProfileResolver? = null,
        liveLinkHolder: LiveSessionLinkHolder = LiveSessionLinkHolder(),
        vehicleReader: VehicleInfoReader = DefaultVehicleInfoReader(),
        clockSyncEnabled: suspend () -> Boolean = { true },
        onSabModeNotGranted: () -> Unit = {}
    ): Pair<DefaultSessionPipeline, LiveSessionLinkHolder> {
        val keyStore = InMemorySecureKeyStore()
        val sessionManager = DefaultSecuritySessionManager(keyStore)
        val controlPointGateway = DefaultControlPointGateway()
        val inputDispatcher = DefaultInputDispatcher(controlPointGateway)
        builtDispatcher = inputDispatcher
        val requestIdGenerator = RequestIdGenerator()
        val projector = DefaultScreenProjector(requestIdGenerator, controlPointGateway)

        val pip = DefaultSessionPipeline(
            sessionManager = sessionManager,
            vehicleReader = vehicleReader,
            assignmentManager = DefaultAssignmentManager(),
            inputDispatcher = inputDispatcher,
            screenProjector = projector,
            requestIdGenerator = requestIdGenerator,
            capabilitiesHolder = capabilitiesHolder,
            controlPointGateway = controlPointGateway,
            sabModeWatchdog = sabModeWatchdog,
            sabModeRetry = sabModeRetry,
            onSabModeNotGranted = onSabModeNotGranted,
            sabModeFallbacks = { sabModeFallbacks },
            activeIndicatorsPublisher = activeIndicatorsPublisher,
            ctsServer = fakeCts,
            clockSyncEnabled = clockSyncEnabled,
            liveLinkHolder = liveLinkHolder,
            announcer = announcer,
            storedProfileResolver = storedProfileResolver
        )
        return pip to liveLinkHolder
    }

    private fun TestScope.session(
        mac: String = MAC,
        origin: SessionOrigin = SessionOrigin.RECEIVER
    ): SabSession = SabSession(
        id = 1L,
        macAddress = mac,
        origin = origin,
        parent = backgroundScope.coroutineContext,
        stallThreshold = null,
        onCloseRequested = { _, _ -> }
    )

    // -----------------------------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------------------------

    @Test
    fun `full pipeline produces Connected with correct vehicle identity`() = runTest {
        val (pipeline, _) = pipeline()
        val session = session()
        val transport = createPrimedTransport()

        val connected = pipeline.open(session, transport) { }

        assertEquals("0583", connected.vehicle.vehicleInfo.seriesCode)
        assertEquals(MAC, connected.session.macAddress)
    }

    @Test
    fun `pipeline reports steps in the correct order`() = runTest {
        val steps = mutableListOf<ConnectionStep>()
        val (pipeline, _) = pipeline()
        val session = session()
        val transport = createPrimedTransport()

        pipeline.open(session, transport) { steps += it }

        assertTrue("must report NEGOTIATING_MTU", ConnectionStep.NEGOTIATING_MTU in steps)
        assertTrue("must report ESTABLISHING_SECURITY", ConnectionStep.ESTABLISHING_SECURITY in steps)
        assertTrue("must report READING_VEHICLE_IDENTITY", ConnectionStep.READING_VEHICLE_IDENTITY in steps)
        assertTrue("must report RUNNING_ASSIGNMENT", ConnectionStep.RUNNING_ASSIGNMENT in steps)
        assertTrue("must report STARTING_ADAPTERS", ConnectionStep.STARTING_ADAPTERS in steps)

        val mtuIdx = steps.indexOf(ConnectionStep.NEGOTIATING_MTU)
        val secIdx = steps.indexOf(ConnectionStep.ESTABLISHING_SECURITY)
        val idIdx = steps.indexOf(ConnectionStep.READING_VEHICLE_IDENTITY)
        val assignIdx = steps.indexOf(ConnectionStep.RUNNING_ASSIGNMENT)
        assertTrue("MTU before security", mtuIdx < secIdx)
        assertTrue("security before identity", secIdx < idIdx)
        assertTrue("identity before assignment", idIdx < assignIdx)
    }

    @Test
    fun `pipeline reports each step exactly once`() = runTest {
        val steps = mutableListOf<ConnectionStep>()
        val (pipeline, _) = pipeline()

        pipeline.open(session(), createPrimedTransport()) { steps += it }

        assertEquals(
            listOf(
                ConnectionStep.NEGOTIATING_MTU,
                ConnectionStep.ESTABLISHING_SECURITY,
                ConnectionStep.READING_VEHICLE_IDENTITY,
                ConnectionStep.ARMING_CHANNELS,
                ConnectionStep.RUNNING_ASSIGNMENT,
                ConnectionStep.STARTING_ADAPTERS
            ),
            steps
        )
    }

    @Test
    fun `pipeline sends REQUEST_SAB_MODE to the BTU`() = runTest {
        val written = mutableListOf<ByteArray>()
        val (pipeline, _) = pipeline()
        val session = session()
        val transport = createPrimedTransport { written += it }

        pipeline.open(session, transport) { }

        assertTrue(
            "REQUEST_SAB_MODE [0x0A] must be sent",
            written.any { it.isNotEmpty() && it[0] == 0x0A.toByte() }
        )
    }

    @Test
    fun `REQUEST_SAB_MODE is written a second time when no 0x09 arrives within the retry timeout`() = runTest {
        val written = mutableListOf<ByteArray>()
        val (pipeline, _) = pipeline(sabModeRetry = SabModeRetry(timeout = 5.seconds))
        val transport = createPrimedTransport { written += it }

        pipeline.open(session(), transport) { }
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals("one request before the timeout", 1, written.count { it.isSabModeRequest() })

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals("the BTU stayed silent: ask once more", 2, written.count { it.isSabModeRequest() })

        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals("and only once more", 2, written.count { it.isSabModeRequest() })
    }

    @Test
    fun `REQUEST_SAB_MODE is written once when the BTU grants SAB_MODE before the retry timeout`() = runTest {
        val written = mutableListOf<ByteArray>()
        val (pipeline, _) = pipeline(sabModeRetry = SabModeRetry(timeout = 5.seconds))
        val transport = createPrimedTransport { written += it }

        pipeline.open(session(), transport) { }
        transport.emitNotification(
            SabGattUuids.SERVICE_DATA_COMM,
            SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
            byteArrayOf(0x09)
        )
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(1, written.count { it.isSabModeRequest() })
    }

    @Test
    fun `a HU_MODE notification before the first grant writes REQUEST_SAB_MODE again when the rider opted in`() = runTest {
        val written = mutableListOf<ByteArray>()
        val (pipeline, _) = pipeline(sabModeFallbacks = SabModeFallbacks(rerequestOnHuMode = true))
        val transport = createPrimedTransport { written += it }

        pipeline.open(session(), transport) { }
        advanceTimeBy(100.milliseconds)
        transport.notifyMode(0x0A)
        runCurrent()

        assertEquals("opening request plus the one the HU_MODE asked for", 2, written.count { it.isSabModeRequest() })
    }

    @Test
    fun `a HU_MODE notification before the first grant writes nothing with the fallbacks off`() = runTest {
        val written = mutableListOf<ByteArray>()
        val (pipeline, _) = pipeline()
        val transport = createPrimedTransport { written += it }

        pipeline.open(session(), transport) { }
        advanceTimeBy(100.milliseconds)
        transport.notifyMode(0x0A)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(1, written.count { it.isSabModeRequest() })
    }

    @Test
    fun `a HU_MODE notification after the first grant is the rider's call and writes nothing`() = runTest {
        val written = mutableListOf<ByteArray>()
        val (pipeline, _) = pipeline(sabModeFallbacks = SabModeFallbacks(rerequestOnHuMode = true))
        val transport = createPrimedTransport { written += it }

        pipeline.open(session(), transport) { }
        advanceTimeBy(100.milliseconds)
        transport.notifyMode(0x09)
        runCurrent()
        advanceTimeBy(1.seconds)
        transport.notifyMode(0x0A)
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals(1, written.count { it.isSabModeRequest() })
    }

    @Test
    fun `the grant is assumed after the timeout request when the rider opted into projecting without it`() = runTest {
        val written = mutableListOf<ByteArray>()
        val (pipeline, _) = pipeline(sabModeFallbacks = SabModeFallbacks(projectWithoutGrant = true))
        val dispatcher = builtDispatcher
        val transport = createPrimedTransport { written += it }

        pipeline.open(session(), transport) { }
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals(ScreenGrant.NONE, dispatcher.screenGrant.value)

        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals("the second request still goes out", 2, written.count { it.isSabModeRequest() })
        assertEquals(ScreenGrant.ASSUMED, dispatcher.screenGrant.value)
        assertEquals("the mode stays what the BTU said", DashboardMode.HU_MODE, dispatcher.currentMode.value)
    }

    @Test
    fun `nothing is assumed after the timeout with the fallbacks off`() = runTest {
        val (pipeline, _) = pipeline()
        val dispatcher = builtDispatcher
        val transport = createPrimedTransport()

        pipeline.open(session(), transport) { }
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals(ScreenGrant.NONE, dispatcher.screenGrant.value)
    }

    @Test
    fun `nothing is assumed when the BTU granted and the rider took the screen back before the timeout`() = runTest {
        val (pipeline, _) = pipeline(sabModeFallbacks = SabModeFallbacks(projectWithoutGrant = true))
        val dispatcher = builtDispatcher
        val transport = createPrimedTransport()

        pipeline.open(session(), transport) { }
        transport.notifyMode(0x09)
        runCurrent()
        transport.notifyMode(0x0A)
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals(ScreenGrant.NONE, dispatcher.screenGrant.value)
    }

    private suspend fun MockGattTransport.notifyMode(code: Int) = emitNotification(
        SabGattUuids.SERVICE_DATA_COMM,
        SabGattUuids.CHAR_FUNCTION_NOTIFICATION,
        byteArrayOf(code.toByte())
    )

    private fun ByteArray.isSabModeRequest(): Boolean = size == 2 && this[0] == 0x0A.toByte()

    @Test
    fun `CTS server is started and stopped on close`() = runTest {
        val cts = FakeCtsServer()
        val (pipeline, _) = pipeline(fakeCts = cts)
        val session = session()
        val transport = createPrimedTransport()

        pipeline.open(session, transport) { }
        assertTrue("CTS must be running after open", cts.isRunning())

        session.close(CloseReason.DISCONNECT)
        assertFalse("CTS must be stopped after close", cts.isRunning())
    }

    @Test
    fun `CTS server is not started when clock sync is off`() = runTest {
        val cts = FakeCtsServer()
        val (pipeline, _) = pipeline(fakeCts = cts, clockSyncEnabled = { false })
        val session = session()
        val transport = createPrimedTransport()

        pipeline.open(session, transport) { }

        assertFalse("CTS must not run with clock sync off", cts.isRunning())
    }

    @Test
    fun `capabilities are adopted and released on close`() = runTest {
        val holder = VehicleCapabilitiesHolder()
        // Seeded to SEGMENT so the adopted value differs from Permissive: cluster display is
        // rider-declared, not read from the GATT fixture below.
        val resolver = StoredVehicleProfileResolver { VehicleProfile(clusterDisplay = ClusterDisplay.SEGMENT) }
        val (pipeline, _) = pipeline(capabilitiesHolder = holder, storedProfileResolver = resolver)
        val session = session()
        val transport = createPrimedTransport()

        pipeline.open(session, transport) { }
        assertTrue("capabilities must be adopted", holder.capabilities.value.segmentDisplay)

        session.close(CloseReason.DISCONNECT)
        assertEquals(
            "capabilities must reset on close",
            com.opensab.sab.session.vehicle.VehicleCapabilities.Permissive,
            holder.capabilities.value
        )
    }

    @Test
    fun `link is published in holder AFTER projector starts`() = runTest {
        val linkHolder = LiveSessionLinkHolder()
        val (pipeline, _) = pipeline(liveLinkHolder = linkHolder)
        val session = session()
        val transport = createPrimedTransport()

        assertNull("link must be null before open", linkHolder.current)

        pipeline.open(session, transport) { }

        assertNotNull("link must be published after open", linkHolder.current)
        assertEquals(session, linkHolder.current!!.session)
        assertEquals(transport, linkHolder.current!!.transport)
    }

    @Test
    fun `link is cleared on session close`() = runTest {
        val linkHolder = LiveSessionLinkHolder()
        val (pipeline, _) = pipeline(liveLinkHolder = linkHolder)
        val session = session()
        val transport = createPrimedTransport()

        pipeline.open(session, transport) { }
        assertNotNull(linkHolder.current)

        session.close(CloseReason.DISCONNECT)
        assertNull("link must be cleared on close", linkHolder.current)
    }

    @Test
    fun `watchdog job is stored in link watchdogJob`() = runTest {
        val linkHolder = LiveSessionLinkHolder()
        val (pipeline, _) = pipeline(
            liveLinkHolder = linkHolder,
            sabModeWatchdog = SabModeWatchdog(timeout = 50.milliseconds)
        )
        val session = session()
        val transport = createPrimedTransport()

        pipeline.open(session, transport) { }

        val link = linkHolder.current!!
        assertNotNull("watchdog job must be stored in the link", link.watchdogJob.get())
        assertTrue("watchdog job must be active", link.watchdogJob.get()!!.isActive)
    }

    @Test
    fun `announceClosing emits the canonical log line`() = runTest {
        recordLog().use { recorded ->
            val (pipeline, _) = pipeline()
            val session = session()
            val transport = createPrimedTransport()

            pipeline.open(session, transport) { }
            pipeline.announceClosing(session)

            assertTrue(
                "expected the canonical disconnecting line, got: ${recorded.lines}",
                recorded.lines.any { it == "Disconnecting OpenSAB session and stopping all adapters" }
            )
        }
    }

    @Test
    fun `announceClosing delegates to announcer`() = runTest {
        val announcer = FakeAnnouncer()
        val (pipeline, _) = pipeline(announcer = announcer)
        val session = session()
        val transport = createPrimedTransport()

        pipeline.open(session, transport) { }
        pipeline.announceClosing(session)

        assertEquals(listOf(1L), announcer.closings)
    }

    @Test
    fun `announceOpened delegates to announcer`() = runTest {
        val announcer = FakeAnnouncer()
        val (pipeline, _) = pipeline(announcer = announcer)
        val session = session()
        val transport = createPrimedTransport()

        pipeline.open(session, transport) { }
        pipeline.announceOpened(session)

        assertEquals(listOf(1L), announcer.opened)
    }

    @Test
    fun `storedProfileResolver is consulted for the vehicle name`() = runTest {
        val resolver = StoredVehicleProfileResolver { VehicleProfile(name = "CBR650R") }
        val (pipeline, _) = pipeline(storedProfileResolver = resolver)
        val session = session()
        val transport = createPrimedTransport(seriesCode = "0536")

        val connected = pipeline.open(session, transport) { }

        assertEquals("CBR650R", connected.vehicle.displayName)
    }

    @Test
    fun `a stored segment profile is adopted before SAB_MODE is requested`() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<String>()
        val holder = VehicleCapabilitiesHolder()
        backgroundScope.launch {
            holder.capabilities.drop(1).collect { events += "adopt:${it.segmentDisplay}" }
        }
        val resolver = StoredVehicleProfileResolver { VehicleProfile(clusterDisplay = ClusterDisplay.SEGMENT) }
        val (pipeline, _) = pipeline(capabilitiesHolder = holder, storedProfileResolver = resolver)
        val session = session()
        val transport = createPrimedTransport { payload ->
            if (payload.isNotEmpty() && payload[0] == 0x0A.toByte()) events += "write:0x0A"
        }

        pipeline.open(session, transport) { }

        assertTrue(
            "capabilities must be adopted before REQUEST_SAB_MODE reaches the wire, got: $events",
            events.indexOf("adopt:true") in 0 until events.indexOf("write:0x0A")
        )
    }

    @Test
    fun `keepalive calls updateActiveIndicators only when publisher is non-null`() = runTest {
        val controlPointGateway = DefaultControlPointGateway()
        val requestIdGenerator = RequestIdGenerator()
        val projector = DefaultScreenProjector(requestIdGenerator, controlPointGateway)
        val indicators = ActiveIndicatorsPublisher(projector)

        val linkHolder = LiveSessionLinkHolder()
        val (pipeline, _) = pipeline(
            activeIndicatorsPublisher = indicators,
            liveLinkHolder = linkHolder
        )
        val session = session()
        val transport = createPrimedTransport()

        pipeline.open(session, transport) { }

        // After 5s the keepalive should fire; it should not throw when publisher is present
        advanceTimeBy(5_500.milliseconds)
        runCurrent()

        // Session is still alive (keepalive did not crash)
        assertTrue("session should not be closed by keepalive", !session.isClosed)
    }

    @Test
    fun `keepalive does not call updateActiveIndicators when publisher is null`() = runTest {
        val linkHolder = LiveSessionLinkHolder()
        val (pipeline, _) = pipeline(
            activeIndicatorsPublisher = null,
            liveLinkHolder = linkHolder
        )
        val session = session()
        val transport = createPrimedTransport()

        pipeline.open(session, transport) { }

        // After 5s the keepalive should fire; must not throw when publisher is absent
        advanceTimeBy(5_500.milliseconds)
        runCurrent()

        assertTrue("session should not be closed by keepalive without publisher", !session.isClosed)
    }

    @Test
    fun `spontaneous disconnection requests session close`() = runTest {
        val closeReasons = mutableListOf<CloseReason>()
        val session = SabSession(
            id = 1L,
            macAddress = MAC,
            origin = SessionOrigin.RECEIVER,
            parent = backgroundScope.coroutineContext,
            stallThreshold = null,
            onCloseRequested = { _, reason -> closeReasons += reason }
        )
        val (pipeline, _) = pipeline()
        val transport = createPrimedTransport()

        pipeline.open(session, transport) { }
        runCurrent()

        // Simulate spontaneous disconnection
        transport.disconnect()
        runCurrent()

        assertTrue(
            "spontaneous disconnection must request close with LINK_LOST",
            closeReasons.contains(CloseReason.LINK_LOST)
        )
    }

    @Test
    fun `transport is disconnected on session close`() = runTest {
        val (pipeline, _) = pipeline()
        val session = session()
        val transport = createPrimedTransport()

        pipeline.open(session, transport) { }
        session.close(CloseReason.DISCONNECT)

        assertEquals(
            "transport must be disconnected on close",
            BleConnectionState.Disconnected,
            transport.connectionState.value
        )
    }

    @Test
    fun `watchdog warns when SAB_MODE is not granted`() = runTest {
        recordLog().use { recorded ->
            val (pipeline, _) = pipeline(sabModeWatchdog = SabModeWatchdog(timeout = 50.milliseconds))
            val session = session()
            val transport = createPrimedTransport()

            pipeline.open(session, transport) { }
            advanceTimeBy(200.milliseconds)
            runCurrent()

            assertTrue(
                "expected the ungranted-handover warning, got: ${recorded.lines}",
                recorded.lines.any { it.contains("has NOT granted SAB_MODE") }
            )
        }
    }

    @Test
    fun `the session-opening wait running out is reported to whoever was wired`() = runTest {
        var notGranted = 0
        val (pipeline, _) = pipeline(
            sabModeWatchdog = SabModeWatchdog(timeout = 50.milliseconds),
            onSabModeNotGranted = { notGranted++ }
        )

        pipeline.open(session(), createPrimedTransport()) { }
        advanceTimeBy(49.milliseconds)
        runCurrent()
        assertEquals(0, notGranted)

        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertEquals(1, notGranted)
    }

    @Test
    fun `session connected is set after pipeline completes`() = runTest {
        val (pipeline, _) = pipeline()
        val session = session()
        val transport = createPrimedTransport()

        val connected = pipeline.open(session, transport) { }

        assertEquals(connected, session.connected)
    }

    private companion object {
        const val MAC = "AA:BB:CC:DD:EE:FF"
    }
}
