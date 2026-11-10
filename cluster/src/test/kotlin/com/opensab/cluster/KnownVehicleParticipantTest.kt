package com.opensab.cluster

import com.opensab.logging.Log
import com.opensab.logging.LogSink
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
import com.opensab.sab.session.vehicle.InMemoryVehicleRepository
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KnownVehicleParticipantTest {

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

    private fun TestScope.connectedSession(origin: SessionOrigin) = SabSession(
        id = 1,
        macAddress = vehicle.macAddress,
        origin = origin,
        parent = backgroundScope.coroutineContext,
        stallThreshold = null,
        onCloseRequested = { _, _ -> }
    ).apply {
        markConnectedForTest(SabSessionState.Connected(vehicle, SecuritySession(vehicle.macAddress, ByteArray(16))))
    }

    @Test
    fun `a session opened from the scanner saves its vehicle`() = runTest {
        val repository = InMemoryVehicleRepository()
        val session = connectedSession(SessionOrigin.SCANNER)

        KnownVehicleParticipant(repository).onSessionStarted(session)
        runCurrent()

        assertEquals(vehicle.macAddress, repository.getVehicle(vehicle.macAddress)?.macAddress)
        session.closeForTest(CloseReason.DISCONNECT)
    }

    @Test
    fun `a session opened by the receiver saves nothing`() = runTest {
        val repository = InMemoryVehicleRepository()
        val session = connectedSession(SessionOrigin.RECEIVER)

        KnownVehicleParticipant(repository).onSessionStarted(session)
        runCurrent()

        assertNull(repository.getVehicle(vehicle.macAddress))
        session.closeForTest(CloseReason.DISCONNECT)
    }

    @Test
    fun `a save cut short by the session closing is not logged as a failure`() = runTest {
        val errors = mutableListOf<String?>()
        val sink = LogSink { priority, _, message, _ -> if (priority == Log.ERROR) errors += message }
        Log.install(sink)
        try {
            val stuck = object : VehicleRepository by InMemoryVehicleRepository() {
                override suspend fun saveVehicle(vehicle: KnownVehicle) = awaitCancellation()
            }
            val session = connectedSession(SessionOrigin.SCANNER)

            KnownVehicleParticipant(stuck).onSessionStarted(session)
            runCurrent()
            session.closeForTest(CloseReason.DISCONNECT)
            runCurrent()

            assertTrue("cancellation is not a persistence failure: $errors", errors.isEmpty())
        } finally {
            Log.uninstall(sink)
        }
    }
}
