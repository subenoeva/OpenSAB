package com.opensab.app.service

import com.opensab.domain.autoconnect.AclPresence
import com.opensab.domain.autoconnect.AutoConnectDecision
import com.opensab.domain.autoconnect.AutoConnectEvaluator
import com.opensab.domain.settings.AppSettings
import com.opensab.sab.session.SessionOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AclBroadcastHandlerTest {

    private val mac = "02:00:00:00:00:AB"
    private val btuName = "HONDA BTU 73FD"
    private val calls = mutableListOf<String>()
    private val presence = AclPresence()

    private fun handler(
        vehicles: Set<String> = setOf(mac.lowercase()),
        settings: AppSettings = AppSettings(),
        activeSessionMac: String? = null,
        readDelay: Duration = Duration.ZERO
    ) = AclBroadcastHandler(
        knownVehicleMacs = { delay(readDelay); vehicles },
        settings = { settings },
        activeSessionMac = { activeSessionMac },
        recordAcl = presence::record,
        connect = { calls += "connect $it"; Job().apply { complete() } },
        disconnect = { calls += "disconnect" },
        budget = 8.seconds
    )

    /**
     * 2026-10-02 16:43: the retry of a failed connection needs to know the motorcycle is still
     * linked, and these broadcasts are the only place the phone says so.
     */
    @Test
    fun everyAclBroadcastIsRecordedWhateverIsDecided() = runTest {
        val quiet = handler(settings = AppSettings(autoConnect = false, autoDisconnect = false))

        quiet.handle(AutoConnectEvaluator.ACTION_ACL_CONNECTED, btuName, mac)
        assertEquals(true, presence.linked(mac).first())

        quiet.handle(AutoConnectEvaluator.ACTION_ACL_DISCONNECTED, btuName, mac)
        assertEquals(false, presence.linked(mac).first())
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun theBroadcastIsRecordedEvenWhenTheBudgetRunsOutBeforeADecision() = runTest {
        handler(readDelay = 30.seconds).handle(AutoConnectEvaluator.ACTION_ACL_CONNECTED, btuName, mac)

        assertEquals(true, presence.linked(mac).first())
    }

    @Test
    fun aclConnectedFromAKnownBtuAsksForTheConnection() = runTest {
        val decision = handler().handle(AutoConnectEvaluator.ACTION_ACL_CONNECTED, btuName, mac)

        assertEquals(AutoConnectDecision.Connect(mac), decision)
        assertEquals(listOf("connect $mac"), calls)
    }

    @Test
    fun aclDisconnectedFromTheActiveSessionAsksForTheDisconnection() = runTest {
        val decision = handler(activeSessionMac = mac)
            .handle(AutoConnectEvaluator.ACTION_ACL_DISCONNECTED, btuName, mac)

        assertEquals(AutoConnectDecision.Disconnect(mac), decision)
        assertEquals(listOf("disconnect"), calls)
    }

    @Test
    fun theStoredSettingsReachTheDecision() = runTest {
        val decision = handler(settings = AppSettings(autoConnect = false))
            .handle(AutoConnectEvaluator.ACTION_ACL_CONNECTED, btuName, mac)

        assertEquals(AutoConnectDecision.Ignore, decision)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun readsThatOutliveTheBudgetGiveUpWithoutConnecting() = runTest {
        val decision = handler(readDelay = 30.seconds)
            .handle(AutoConnectEvaluator.ACTION_ACL_CONNECTED, btuName, mac)

        assertNull(decision)
        assertEquals(8_000L, testScheduler.currentTime)
        advanceTimeBy(60.seconds)
        assertEquals(emptyList<String>(), calls)
    }

    private class RecordingLauncher : SessionLauncher {
        val calls = mutableListOf<String>()
        override fun start(macAddress: String, origin: SessionOrigin) {
            calls += "start $macAddress $origin"
        }
        override fun stop(origin: SessionOrigin) {
            calls += "stop $origin"
        }
    }

    private val launcher = RecordingLauncher()

    /** Real bootstrapper with the 5 s claim of the graph; [claimAfter] null means no activity ever claims. */
    private fun bootstrapper(scope: CoroutineScope, overlay: Boolean = true, claimAfter: Duration? = null): SessionBootstrapper {
        lateinit var bootstrapper: SessionBootstrapper
        bootstrapper = SessionBootstrapper(
            launcher = launcher,
            overlayGranted = { overlay },
            launchActivity = {
                claimAfter?.let { scope.launch { delay(it); bootstrapper.startClaimed() } }
                true
            },
            scope = scope,
            claimTimeout = 5.seconds
        )
        return bootstrapper
    }

    private fun handlerOver(bootstrapper: SessionBootstrapper, readDelay: Duration = Duration.ZERO) = AclBroadcastHandler(
        knownVehicleMacs = { delay(readDelay); setOf(mac) },
        settings = { AppSettings() },
        activeSessionMac = { null },
        recordAcl = presence::record,
        connect = bootstrapper::connect,
        disconnect = bootstrapper::disconnect,
        budget = 8.seconds
    )

    @Test
    fun withAnActivityThatNeverClaimsHandleWaitsForTheFallbackStart() = runTest {
        handlerOver(bootstrapper(backgroundScope)).handle(AutoConnectEvaluator.ACTION_ACL_CONNECTED, btuName, mac)

        assertEquals(listOf("start $mac RECEIVER"), launcher.calls)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun whenTheActivityClaimsHandleReturnsWithoutWaitingForTheFallback() = runTest {
        handlerOver(bootstrapper(backgroundScope, claimAfter = 1.seconds))
            .handle(AutoConnectEvaluator.ACTION_ACL_CONNECTED, btuName, mac)

        assertEquals(listOf("start $mac RECEIVER"), launcher.calls)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun whenTheBudgetRunsOutTheFallbackStillFiresAfterwards() = runTest {
        val decision = handlerOver(bootstrapper(backgroundScope), readDelay = 4.seconds)
            .handle(AutoConnectEvaluator.ACTION_ACL_CONNECTED, btuName, mac)

        assertEquals(AutoConnectDecision.Connect(mac), decision)
        assertEquals(8_000L, testScheduler.currentTime)
        assertEquals(emptyList<String>(), launcher.calls)

        advanceTimeBy(1_001L)
        assertEquals(listOf("start $mac RECEIVER"), launcher.calls)
    }

    @Test
    fun withoutTheOverlayPermissionHandleWaitsForNothing() = runTest {
        handlerOver(bootstrapper(backgroundScope, overlay = false))
            .handle(AutoConnectEvaluator.ACTION_ACL_CONNECTED, btuName, mac)

        assertEquals(listOf("start $mac RECEIVER"), launcher.calls)
        assertEquals(0L, testScheduler.currentTime)
    }
}
