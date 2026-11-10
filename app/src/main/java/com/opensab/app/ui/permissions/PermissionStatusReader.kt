package com.opensab.app.ui.permissions

import com.opensab.domain.call.CallAccessPolicy
import com.opensab.domain.call.CallAccessState

/**
 * Raw platform facts, no rule. Also the one place in the app that knows the API level, which is why
 * [PermissionCatalog.entriesFor] takes it as an argument.
 */
interface PermissionProbe {

    val sdkInt: Int

    fun isPermissionGranted(permission: String): Boolean

    fun isNotificationListenerEnabled(): Boolean

    fun isIgnoringBatteryOptimizations(): Boolean

    fun isBluetoothEnabled(): Boolean

    fun areLocationServicesEnabled(): Boolean

    fun canDrawOverlays(): Boolean
}

/**
 * What the ViewModels get injected. The production implementation, [DefaultPermissionStatusReader],
 * is pure; the `Context` lives one layer down in [AndroidPermissionProbe].
 */
interface PermissionStatusReader {

    fun statusOf(entry: PermissionEntry): PermissionStatus
}

/**
 * The mapping of entries to status is the thing worth testing, so it stays pure and only the
 * [PermissionProbe] touches the platform.
 *
 * `statusOf` does not suspend: these are reads of state the process already holds. That lets the
 * ViewModels call it from `init`, so the first state the UI sees is already true.
 */
class DefaultPermissionStatusReader(
    private val probe: PermissionProbe
) : PermissionStatusReader {

    override fun statusOf(entry: PermissionEntry): PermissionStatus = when (entry.id) {
        PermissionId.NOTIFICATION_ACCESS -> probe.isNotificationListenerEnabled().asStatus()
        PermissionId.BATTERY_UNRESTRICTED -> probe.isIgnoringBatteryOptimizations().asStatus()
        PermissionId.DRAW_OVERLAYS -> probe.canDrawOverlays().asStatus()
        PermissionId.BLUETOOTH_ENABLED -> probe.isBluetoothEnabled().asStatus()
        PermissionId.LOCATION_SERVICES -> probe.areLocationServicesEnabled().asStatus()
        PermissionId.ANSWER_CALLS -> telephonyStatus(entry)
        else -> entry.permissions.all(probe::isPermissionGranted).asStatus()
    }

    /**
     * The one row whose "no" can be permanent: below API 31 there is no `Notification$CallStyle`,
     * and `CallAccessPolicy` decides. Above it, the permission decides like any other runtime row.
     */
    private fun telephonyStatus(entry: PermissionEntry): PermissionStatus {
        val listenerEnabled = probe.isNotificationListenerEnabled()
        val answerGranted = entry.permissions.all(probe::isPermissionGranted)
        val callAccess = CallAccessPolicy.resolve(probe.sdkInt, listenerEnabled)

        return if (callAccess == CallAccessState.UNSUPPORTED) {
            PermissionStatus.UNSUPPORTED
        } else {
            answerGranted.asStatus()
        }
    }

    private fun Boolean.asStatus(): PermissionStatus =
        if (this) PermissionStatus.GRANTED else PermissionStatus.DENIED
}
