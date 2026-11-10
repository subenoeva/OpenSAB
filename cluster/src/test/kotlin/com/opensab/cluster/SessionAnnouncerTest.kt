package com.opensab.cluster

import com.opensab.domain.audio.Cue
import com.opensab.domain.audio.FakeHeadsetPresence
import com.opensab.domain.audio.MockCuePlayer
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.CloseReason
import com.opensab.sab.session.SabSession
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.closeForTest
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.crypto.SecuritySession
import com.opensab.sab.session.markConnectedForTest
import com.opensab.sab.session.vehicle.KnownVehicle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SessionAnnouncerTest {

    @Test
    fun cueSessionAnnouncerPlaysLinkUpOnOpened() = runTest {
        val cuePlayer = MockCuePlayer()
        val announcer = CueSessionAnnouncer(cuePlayer, FakeHeadsetPresence(connected = true), settle = Duration.ZERO)
        val session = createSession(backgroundScope.coroutineContext, isConnected = false)

        announcer.announceOpened(session)
        runCurrent()

        assertEquals(listOf(Cue.LINK_UP), cuePlayer.played)
    }

    /**
     * `CUE-UNHEARD`: 4 of 6 LINK_UP sounded on the phone's speaker because the intercom was not on
     * A2DP yet when the session opened. The cue waits for the headset instead.
     */
    @Test
    fun linkUpWaitsForTheHeadsetBeforeSounding() = runTest {
        val cuePlayer = MockCuePlayer()
        val headset = FakeHeadsetPresence(connected = false)
        val session = createSession(backgroundScope.coroutineContext, isConnected = false)

        CueSessionAnnouncer(cuePlayer, headset, settle = Duration.ZERO).announceOpened(session)
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue("got: ${cuePlayer.played}", cuePlayer.played.isEmpty())

        headset.state.value = true
        runCurrent()

        assertEquals(listOf(Cue.LINK_UP), cuePlayer.played)
    }

    @Test
    fun linkUpSoundsWhereThePhoneRoutesItWhenNoHeadsetArrivesInTime() = runTest {
        val cuePlayer = MockCuePlayer()
        val session = createSession(backgroundScope.coroutineContext, isConnected = false)

        CueSessionAnnouncer(cuePlayer, FakeHeadsetPresence(connected = false), settle = Duration.ZERO).announceOpened(session)
        advanceTimeBy(CueSessionAnnouncer.HEADSET_WAIT.inWholeMilliseconds - 1)
        runCurrent()
        assertTrue(cuePlayer.played.isEmpty())

        advanceTimeBy(2)
        runCurrent()

        assertEquals(listOf(Cue.LINK_UP), cuePlayer.played)
    }

    @Test
    fun linkUpIsDroppedWhenTheSessionClosesBeforeTheHeadsetArrives() = runTest {
        val cuePlayer = MockCuePlayer()
        val headset = FakeHeadsetPresence(connected = false)
        val session = createSession(backgroundScope.coroutineContext, isConnected = false)

        CueSessionAnnouncer(cuePlayer, headset, settle = Duration.ZERO).announceOpened(session)
        runCurrent()
        session.closeForTest(CloseReason.LINK_LOST)
        headset.state.value = true
        advanceTimeBy(CueSessionAnnouncer.HEADSET_WAIT.inWholeMilliseconds + 1)
        runCurrent()

        assertTrue(cuePlayer.played.isEmpty())
    }

    @Test
    fun cueSessionAnnouncerPlaysLinkDownOnClosingWhenConnected() = runTest {
        val cuePlayer = MockCuePlayer()
        val announcer = CueSessionAnnouncer(cuePlayer, FakeHeadsetPresence(connected = true), settle = Duration.ZERO)
        val session = createSession(backgroundScope.coroutineContext, isConnected = true)
        announcer.announceOpened(session)
        runCurrent()

        announcer.announceClosing(session)

        assertEquals(listOf(Cue.LINK_UP, Cue.LINK_DOWN), cuePlayer.played)
    }

    /**
     * 2026-10-01 21:11 and 2026-10-08 21:12: the link dropped 6.1 s and 2.3 s after connecting, while
     * LINK_UP was still waiting for the headset. The rider heard "gone" for a link never announced.
     */
    @Test
    fun linkDownStaysSilentWhenTheSessionClosesBeforeItsLinkUp() = runTest {
        val cuePlayer = MockCuePlayer()
        val announcer = CueSessionAnnouncer(cuePlayer, FakeHeadsetPresence(connected = false), settle = Duration.ZERO)
        val session = createSession(backgroundScope.coroutineContext, isConnected = true)

        announcer.announceOpened(session)
        advanceTimeBy(2_300)
        runCurrent()
        announcer.announceClosing(session)
        session.closeForTest(CloseReason.LINK_LOST)
        advanceTimeBy(CueSessionAnnouncer.HEADSET_WAIT.inWholeMilliseconds)
        runCurrent()

        assertTrue("got: ${cuePlayer.played}", cuePlayer.played.isEmpty())
    }

    /**
     * 2026-10-03 13:16: 170 ms after the key-off drop the BTU linked again, the headset was already
     * there, LINK_UP sounded at once and the link died 2.3 s later. Three cues in six seconds for
     * one key-off.
     */
    @Test
    fun aLinkThatDoesNotOutliveTheSettleTimeIsNeverAnnounced() = runTest {
        val cuePlayer = MockCuePlayer()
        val announcer = CueSessionAnnouncer(cuePlayer, FakeHeadsetPresence(connected = true), settle = 3.seconds)
        val session = createSession(backgroundScope.coroutineContext, isConnected = true)

        announcer.announceOpened(session)
        advanceTimeBy(2_300)
        runCurrent()
        announcer.announceClosing(session)
        session.closeForTest(CloseReason.LINK_LOST)
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue("got: ${cuePlayer.played}", cuePlayer.played.isEmpty())
    }

    @Test
    fun aLinkThatSettlesIsAnnouncedAndSoIsItsEnd() = runTest {
        val cuePlayer = MockCuePlayer()
        val announcer = CueSessionAnnouncer(cuePlayer, FakeHeadsetPresence(connected = true), settle = 3.seconds)
        val session = createSession(backgroundScope.coroutineContext, isConnected = true)

        announcer.announceOpened(session)
        advanceTimeBy(2_999)
        runCurrent()
        assertTrue("got: ${cuePlayer.played}", cuePlayer.played.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(Cue.LINK_UP), cuePlayer.played)

        announcer.announceClosing(session)

        assertEquals(listOf(Cue.LINK_UP, Cue.LINK_DOWN), cuePlayer.played)
    }

    /** The settle time runs alongside the wait for the headset, not after it. */
    @Test
    fun aHeadsetThatArrivesAfterTheSettleTimeIsNotMadeToWaitAgain() = runTest {
        val cuePlayer = MockCuePlayer()
        val headset = FakeHeadsetPresence(connected = false)
        val session = createSession(backgroundScope.coroutineContext, isConnected = true)

        CueSessionAnnouncer(cuePlayer, headset, settle = 3.seconds).announceOpened(session)
        advanceTimeBy(6_000)
        runCurrent()
        headset.state.value = true
        runCurrent()

        assertEquals(listOf(Cue.LINK_UP), cuePlayer.played)
    }

    /** A second session must not inherit the first one's announcement. */
    @Test
    fun theAnnouncementOfOneSessionDoesNotCoverTheNext() = runTest {
        val cuePlayer = MockCuePlayer()
        val announcer = CueSessionAnnouncer(cuePlayer, FakeHeadsetPresence(connected = true), settle = 3.seconds)
        val first = createSession(backgroundScope.coroutineContext, isConnected = true, id = 1L)
        announcer.announceOpened(first)
        advanceTimeBy(3_001)
        runCurrent()
        announcer.announceClosing(first)
        first.closeForTest(CloseReason.LINK_LOST)
        cuePlayer.reset()

        val second = createSession(backgroundScope.coroutineContext, isConnected = true, id = 2L)
        announcer.announceOpened(second)
        advanceTimeBy(1_000)
        runCurrent()
        announcer.announceClosing(second)

        assertTrue("got: ${cuePlayer.played}", cuePlayer.played.isEmpty())
    }

    @Test
    fun cueSessionAnnouncerDoesNotPlayLinkDownOnClosingWhenNotConnected() = runTest {
        val cuePlayer = MockCuePlayer()
        val announcer = CueSessionAnnouncer(cuePlayer, FakeHeadsetPresence(connected = true), settle = Duration.ZERO)
        val session = createSession(backgroundScope.coroutineContext, isConnected = false)

        announcer.announceClosing(session)

        assertTrue(cuePlayer.played.isEmpty())
    }

    // storedProfileResolver() (the shared StoredVehicleProfileResolver over a VehicleRepository)
    // is proven once, in :sab:session's own StoredProfileResolverTest, against the same
    // InMemoryVehicleRepository this module wires for the real app.

    private fun createSession(parentContext: CoroutineContext, isConnected: Boolean, id: Long = 1L): SabSession {
        val session = SabSession(
            id = id,
            macAddress = "AA:BB:CC:DD:EE:FF",
            origin = SessionOrigin.SCANNER,
            parent = parentContext,
            stallThreshold = null,
            onCloseRequested = { _, _ -> }
        )
        if (isConnected) {
            val vehicle = KnownVehicle(
                macAddress = "AA:BB:CC:DD:EE:FF",
                displayName = "Honda",
                vehicleInfo = VehicleInformation(
                    vehicleType = 0x01,
                    displayCapability = byteArrayOf(0x01, 0x02),
                    switchType = SwitchType.TYPE_B,
                    categoryId = 0x01,
                    seriesCode = "0583",
                    destinationId = DestinationId.EU
                )
            )
            session.markConnectedForTest(
                SabSessionState.Connected(
                    vehicle = vehicle,
                    session = SecuritySession("AA:BB:CC:DD:EE:FF", ByteArray(16))
                )
            )
        }
        return session
    }
}
