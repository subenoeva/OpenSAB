package com.opensab.app.ui

import android.Manifest
import android.os.Build
import com.opensab.app.ui.permissions.PermissionCatalog
import com.opensab.app.ui.permissions.PermissionId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app asks for so it can find and reach the motorcycle, per API level: the `BLUETOOTH`
 * row of [PermissionCatalog].
 */
class BluetoothPermissionGateTest {

    private fun bluetoothPermissions(sdkInt: Int): List<String> =
        PermissionCatalog.entriesFor(sdkInt)
            .single { it.id == PermissionId.BLUETOOTH }
            .permissions

    @Test
    fun `android 12 and later asks for the dedicated bluetooth permissions`() {
        val permissions = bluetoothPermissions(sdkInt = Build.VERSION_CODES.S)

        assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertFalse(
            "Fine location is no longer needed for BLE discovery on API 31+",
            permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    @Test
    fun `android 11 and earlier falls back to fine location for discovery`() {
        val permissions = bluetoothPermissions(sdkInt = Build.VERSION_CODES.R)

        assertTrue(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(permissions.contains(Manifest.permission.BLUETOOTH_SCAN))
    }

    /** `POST_NOTIFICATIONS` is its own row, so a "no" to it can never block the connection. */
    @Test
    fun `notification permission is only requested on android 13 and later`() {
        fun notificationsRow(sdkInt: Int) = PermissionCatalog.entriesFor(sdkInt)
            .firstOrNull { it.id == PermissionId.NOTIFICATIONS }

        assertNotNull(notificationsRow(Build.VERSION_CODES.TIRAMISU))
        assertNull(notificationsRow(Build.VERSION_CODES.S))
    }
}
