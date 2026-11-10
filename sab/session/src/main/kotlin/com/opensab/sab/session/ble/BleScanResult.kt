package com.opensab.sab.session.ble

/**
 * Strips non-printable characters, control codes and null padding from BLE advertisement names.
 *
 * An advertisement with nothing usable in it yields an empty string rather than a placeholder: the
 * BLE layer has no Context, and the screen already falls back to a localized name for a blank one.
 */
fun sanitizeDeviceName(rawName: String?): String {
    if (rawName.isNullOrBlank()) return ""
    return rawName.filter { it >= ' ' && !it.isISOControl() }.trim()
}

/**
 * Replaces the entry sharing [device]'s address, or appends it, keeping discovery order stable so
 * the list does not reshuffle under the user as RSSI values fluctuate.
 */
fun List<ScannedDevice>.upsertByAddress(device: ScannedDevice): List<ScannedDevice> {
    val index = indexOfFirst { it.address == device.address }
    return if (index == -1) this + device else toMutableList().apply { this[index] = device }
}
