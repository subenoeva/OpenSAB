package com.opensab.sab.session.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Values from Bluetooth Core, Vol 3, Part G, 3.3.3.3 (Client Characteristic Configuration). */
class CccdTest {

    @Test
    fun theDescriptorIs0x2902() =
        assertEquals(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), CCCD_UUID)

    @Test
    fun enablesNotificationsWith0100() =
        assertArrayEquals(byteArrayOf(0x01, 0x00), cccdValue(enabled = true, supportsIndicate = false))

    @Test
    fun enablesIndicationsWhenTheCharacteristicIndicates() =
        assertArrayEquals(byteArrayOf(0x02, 0x00), cccdValue(enabled = true, supportsIndicate = true))

    @Test
    fun disablesWith0000WhateverItSupports() {
        assertArrayEquals(byteArrayOf(0x00, 0x00), cccdValue(enabled = false, supportsIndicate = true))
        assertArrayEquals(byteArrayOf(0x00, 0x00), cccdValue(enabled = false, supportsIndicate = false))
    }
}
