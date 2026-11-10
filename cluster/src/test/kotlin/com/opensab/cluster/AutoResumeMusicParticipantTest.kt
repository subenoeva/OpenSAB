package com.opensab.cluster

import com.opensab.domain.music.RecordingMusicManager
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.CloseReason
import com.opensab.sab.session.SabSession
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.SessionOrigin
import com.opensab.sab.session.closeForTest
import com.opensab.sab.session.crypto.SecuritySession
import com.opensab.sab.session.markConnectedForTest
import com.opensab.sab.session.vehicle.KnownVehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoResumeMusicParticipantTest {

    private val vehicle = KnownVehicle(
        macAddress = "AA:BB:CC:DD:EE:FF",
        displayName = "Honda Forza 750",
        vehicleInfo = VehicleInformation(
            vehicleType = 0x01,
            displayCapability = byteArrayOf(0x01, 0x02),
            switchType = SwitchType.TYPE_B,
            categoryId = 0x01,
            seriesCode = "RH10",
            destinationId = DestinationId.EU
        )
    )

    private fun TestScope.connectedSession() = SabSession(
        id = 1,
        macAddress = vehicle.macAddress,
        origin = SessionOrigin.RECEIVER,
        parent = backgroundScope.coroutineContext,
        stallThreshold = null,
        onCloseRequested = { _, _ -> }
    ).apply {
        markConnectedForTest(SabSessionState.Connected(vehicle, SecuritySession(vehicle.macAddress, ByteArray(16))))
    }

    @Test
    fun playsOnceWhenTheSessionStartsAndTheSettingSaysSo() = runTest {
        val music = RecordingMusicManager()
        val session = connectedSession()

        AutoResumeMusicParticipant(music, enabled = { true }).onSessionStarted(session)
        runCurrent()

        assertEquals(1, music.playCalls)
        session.closeForTest(CloseReason.DISCONNECT)
    }

    @Test
    fun doesNothingWhenTheSettingSaysNo() = runTest {
        val music = RecordingMusicManager()
        val session = connectedSession()

        AutoResumeMusicParticipant(music, enabled = { false }).onSessionStarted(session)
        runCurrent()

        assertEquals(0, music.playCalls)
        session.closeForTest(CloseReason.DISCONNECT)
    }
}
