package com.opensab.app.ui.permissions

/**
 * A system screen named rather than built: an `Intent` cannot be constructed in a JVM test, so the
 * choice is a value here and the composable turns it into an `Intent`.
 */
sealed interface SystemScreenTarget {

    data object NotificationListenerSettings : SystemScreenTarget

    /** `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which asks in a dialog. */
    data class BatteryExemptionRequest(val packageName: String) : SystemScreenTarget

    /** `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`: the list, with OpenSAB inside it. */
    data object BatteryOptimizationList : SystemScreenTarget

    data object BluetoothSettings : SystemScreenTarget

    data object LocationSourceSettings : SystemScreenTarget

    /** `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`, scoped to OpenSAB's package. */
    data class ManageOverlayPermission(val packageName: String) : SystemScreenTarget
}

object SystemScreenPolicy {

    /**
     * The candidates, best first; the UI launches the first that resolves against the
     * `PackageManager`. A runtime row gets an empty list: it has a dialog, not a screen.
     *
     * Only the battery has two. The direct dialog resolves to nothing on some OEM skins; the
     * fallback opens the whole application list and needs no manifest declaration, so it is also
     * the escape hatch if `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` ever fails Play review: drop the
     * manifest line and this falls back on its own.
     */
    fun screensFor(id: PermissionId, packageName: String): List<SystemScreenTarget> = when (id) {
        PermissionId.NOTIFICATION_ACCESS -> listOf(SystemScreenTarget.NotificationListenerSettings)
        PermissionId.BATTERY_UNRESTRICTED -> listOf(
            SystemScreenTarget.BatteryExemptionRequest(packageName),
            SystemScreenTarget.BatteryOptimizationList
        )
        PermissionId.DRAW_OVERLAYS -> listOf(SystemScreenTarget.ManageOverlayPermission(packageName))
        PermissionId.BLUETOOTH_ENABLED -> listOf(SystemScreenTarget.BluetoothSettings)
        PermissionId.LOCATION_SERVICES -> listOf(SystemScreenTarget.LocationSourceSettings)
        PermissionId.BLUETOOTH,
        PermissionId.NOTIFICATIONS,
        PermissionId.LOCATION_WEATHER,
        PermissionId.MICROPHONE,
        PermissionId.CONTACTS,
        PermissionId.PLACE_CALLS,
        PermissionId.ANSWER_CALLS,
        PermissionId.SEND_SMS -> emptyList()
    }
}
