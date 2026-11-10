package com.opensab.sab.session.ble

import java.util.UUID

/** Descriptor 0x2902, Client Characteristic Configuration. */
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * What to write into the CCCD. `setCharacteristicNotification` alone is not enough: without this
 * write the peripheral never starts sending.
 */
fun cccdValue(enabled: Boolean, supportsIndicate: Boolean): ByteArray = when {
    !enabled -> byteArrayOf(0x00, 0x00)
    supportsIndicate -> byteArrayOf(0x02, 0x00)
    else -> byteArrayOf(0x01, 0x00)
}
