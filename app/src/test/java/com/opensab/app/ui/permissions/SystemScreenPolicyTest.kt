package com.opensab.app.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An `Intent` cannot be built in a JVM test (`android.jar` is a stub that throws), so the choice of
 * screen is pinned here as a value; the composable launches the first candidate that resolves.
 */
class SystemScreenPolicyTest {

    private val packageName = "com.opensab"

    /**
     * The direct request asks for the exemption in a dialog. It does not resolve on every OEM skin,
     * and the fallback opens the full application list, where the rider has to find OpenSAB:
     * worse, but reachable, and it needs no manifest declaration at all.
     */
    @Test
    fun `should offer the direct battery request before the list`() {
        assertEquals(
            listOf(
                SystemScreenTarget.BatteryExemptionRequest(packageName),
                SystemScreenTarget.BatteryOptimizationList
            ),
            SystemScreenPolicy.screensFor(PermissionId.BATTERY_UNRESTRICTED, packageName)
        )
    }

    @Test
    fun `should carry the package name into the direct battery request`() {
        val screens =
            SystemScreenPolicy.screensFor(PermissionId.BATTERY_UNRESTRICTED, "com.example.other")

        assertEquals(SystemScreenTarget.BatteryExemptionRequest("com.example.other"), screens.first())
    }

    @Test
    fun `should send the special accesses and the toggles to one screen each`() {
        assertEquals(
            listOf(SystemScreenTarget.NotificationListenerSettings),
            SystemScreenPolicy.screensFor(PermissionId.NOTIFICATION_ACCESS, packageName)
        )
        assertEquals(
            listOf(SystemScreenTarget.BluetoothSettings),
            SystemScreenPolicy.screensFor(PermissionId.BLUETOOTH_ENABLED, packageName)
        )
        assertEquals(
            listOf(SystemScreenTarget.LocationSourceSettings),
            SystemScreenPolicy.screensFor(PermissionId.LOCATION_SERVICES, packageName)
        )
    }

    /** A runtime row has a dialog, not a screen. */
    @Test
    fun `should offer no screen for a runtime entry`() {
        val runtimeIds = PermissionCatalog.entriesFor(sdkInt = 36)
            .filter { it.kind == PermissionKind.RUNTIME }
            .map { it.id }

        assertEquals(8, runtimeIds.size)
        runtimeIds.forEach { id ->
            assertEquals(
                "$id",
                emptyList<SystemScreenTarget>(),
                SystemScreenPolicy.screensFor(id, packageName)
            )
        }
    }
}
