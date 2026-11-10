package com.opensab.sab.session.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure scan-result helpers that back [AndroidBleScanner]. The platform-bound
 * parts need an instrumented device; these cover the logic that decides what the rider sees.
 */
class BleScanResultTest {

    private fun device(address: String, rssi: Int = -60, name: String = "HONDA-BTU") =
        ScannedDevice(name = name, address = address, rssi = rssi)

    // region Scan result de-duplication

    @Test
    fun `upsert appends a device that has not been seen before`() {
        val result = emptyList<ScannedDevice>().upsertByAddress(device("AA:BB:CC:11:22:33"))

        assertEquals(1, result.size)
        assertEquals("AA:BB:CC:11:22:33", result.first().address)
    }

    @Test
    fun `upsert replaces the existing entry instead of duplicating the same address`() {
        val initial = listOf(device("AA:BB:CC:11:22:33", rssi = -80))

        val result = initial.upsertByAddress(device("AA:BB:CC:11:22:33", rssi = -45))

        assertEquals("A repeated advertisement must not add a row", 1, result.size)
        assertEquals(-45, result.first().rssi)
    }

    @Test
    fun `upsert keeps discovery order stable when refreshing a device in the middle`() {
        val initial = listOf(
            device("AA:00", name = "first"),
            device("BB:11", name = "second"),
            device("CC:22", name = "third")
        )

        val result = initial.upsertByAddress(device("BB:11", rssi = -30, name = "second"))

        assertEquals(listOf("AA:00", "BB:11", "CC:22"), result.map { it.address })
        assertEquals(-30, result[1].rssi)
    }

    @Test
    fun `upsert does not mutate the original list`() {
        val initial = listOf(device("AA:00", rssi = -70))

        initial.upsertByAddress(device("AA:00", rssi = -20))

        assertEquals("Source list must stay untouched", -70, initial.first().rssi)
    }

    // endregion

    // region Device name sanitization

    @Test
    fun `sanitizeDeviceName removes trailing null bytes and control characters`() {
        val raw = "HONDA BTU 73FD\u0000\u0000\u0001\u0002"
        val cleaned = sanitizeDeviceName(raw)
        assertEquals("HONDA BTU 73FD", cleaned)
    }

    @Test
    fun `sanitizeDeviceName trims leading and trailing whitespace`() {
        val raw = "   HONDA BTU 73FD   "
        val cleaned = sanitizeDeviceName(raw)
        assertEquals("HONDA BTU 73FD", cleaned)
    }

    @Test
    fun `sanitizeDeviceName yields a blank name for null or unusable input`() {
        // Blank, not a placeholder: the screen owns the localized fallback, and this layer
        // has no Context, so a literal here would hardcode English into the BLE stack.
        assertEquals("", sanitizeDeviceName(null))
        assertEquals("", sanitizeDeviceName(""))
        assertEquals("", sanitizeDeviceName("   "))
        assertEquals("", sanitizeDeviceName("\u0000\u0001\u0002"))
    }

    // endregion
}
