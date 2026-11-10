package com.opensab.sab.session.dfu

import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.session.ble.MockGattTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DFUManagerTest {

    @Test
    fun startUpdateStreamsChunksAndCompletesSuccessfully() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()

        val writtenPackets = mutableListOf<ByteArray>()
        transport.onCharacteristicWrite = { uuid, payload ->
            if (uuid == SabGattUuids.CHAR_DFU_PACKET) {
                writtenPackets.add(payload.clone())
            }
        }

        val manager: DFUManager = DefaultDFUManager(chunkSizeBytes = 256)
        assertEquals(DFUState.Idle, manager.state.value)

        // Create sample firmware binary of 600 bytes (requires 3 chunks: 256 + 256 + 88)
        val sampleFirmware = ByteArray(600) { (it % 256).toByte() }

        val result = manager.startUpdate(sampleFirmware, transport)
        assertTrue("Expected update to succeed", result.isSuccess)
        assertEquals(DFUState.Completed, manager.state.value)

        // Verify 3 chunks written to CHAR_DFU_PACKET
        assertEquals(3, writtenPackets.size)
        assertEquals(256, writtenPackets[0].size)
        assertEquals(256, writtenPackets[1].size)
        assertEquals(88, writtenPackets[2].size)
    }

    @Test
    fun abortUpdateSendsAbortCommandAndResetsToIdle() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()

        val manager: DFUManager = DefaultDFUManager()
        val result = manager.abortUpdate(transport)

        assertTrue(result.isSuccess)
        assertEquals(DFUState.Idle, manager.state.value)

        // Verify CHAR_DFU_CONTROL_POINT has ABORT_DFU command (0x02)
        val readResult = transport.readCharacteristic(
            SabGattUuids.SERVICE_DFU,
            SabGattUuids.CHAR_DFU_CONTROL_POINT
        )
        assertTrue(readResult.isSuccess)
        assertEquals(0x02.toByte(), readResult.getOrNull()?.get(0))
    }
}
