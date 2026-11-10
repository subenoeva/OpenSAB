package com.opensab.app.ui.permissions

/**
 * Doubles the platform facts, not the reader: doubling the reader would replace the very rule
 * `PermissionStatusTest` asserts.
 */
class FakePermissionProbe(
    override var sdkInt: Int = 36,
    var granted: Set<String> = emptySet(),
    var notificationListenerEnabled: Boolean = false,
    var ignoringBatteryOptimizations: Boolean = false,
    var bluetoothEnabled: Boolean = false,
    var locationServicesEnabled: Boolean = false,
    var canDrawOverlays: Boolean = false
) : PermissionProbe {

    /** Which permissions were looked up, so a test can assert that a row did not ask at all. */
    val permissionLookups = mutableListOf<String>()

    override fun isPermissionGranted(permission: String): Boolean {
        permissionLookups += permission
        return permission in granted
    }

    override fun isNotificationListenerEnabled(): Boolean = notificationListenerEnabled

    override fun isIgnoringBatteryOptimizations(): Boolean = ignoringBatteryOptimizations

    override fun isBluetoothEnabled(): Boolean = bluetoothEnabled

    override fun areLocationServicesEnabled(): Boolean = locationServicesEnabled

    override fun canDrawOverlays(): Boolean = canDrawOverlays
}
