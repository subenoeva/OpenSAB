package com.opensab.app.ui.permissions

import com.opensab.app.R

enum class PermissionsTone { BLOCKING, COMPLETE, PARTIAL }

/**
 * `UNSUPPORTED` is out of both halves of the count: left in the denominator, the bar would never
 * fill on Android 11 no matter what is granted.
 */
data class PermissionsProgress(val granted: Int, val grantable: Int, val tone: PermissionsTone) {
    val fraction: Float get() = if (grantable == 0) 0f else granted.toFloat() / grantable
}

fun List<PermissionRow>.progress(): PermissionsProgress {
    val grantable = count { it.status != PermissionStatus.UNSUPPORTED }
    val granted = count { it.status == PermissionStatus.GRANTED }
    val tone = when {
        any { it.tone == PermissionRowTone.BLOCKING } -> PermissionsTone.BLOCKING
        grantable > 0 && granted == grantable -> PermissionsTone.COMPLETE
        else -> PermissionsTone.PARTIAL
    }
    return PermissionsProgress(granted, grantable, tone)
}

enum class PermissionRowTone { BLOCKING, GRANTED, UNSUPPORTED, PENDING }

val PermissionRow.tone: PermissionRowTone
    get() = when {
        entry.essential && status == PermissionStatus.DENIED -> PermissionRowTone.BLOCKING
        status == PermissionStatus.GRANTED -> PermissionRowTone.GRANTED
        status == PermissionStatus.UNSUPPORTED -> PermissionRowTone.UNSUPPORTED
        else -> PermissionRowTone.PENDING
    }

/**
 * `RequestMultiplePermissions` over a permission already held returns at once without drawing a
 * dialog, so anything but a `DENIED` row would be a dead control.
 */
val PermissionRow.isActionable: Boolean get() = status == PermissionStatus.DENIED

/** Only answering calls has its own unsupported text: it explains the Android 12 cut. */
internal fun PermissionRow.descriptionRes(): Int = when {
    status == PermissionStatus.UNSUPPORTED && entry.id == PermissionId.ANSWER_CALLS ->
        R.string.permission_answer_calls_unsupported_desc

    else -> entry.id.descriptionRes()
}

/** Where the tap leads: a system dialog and a walk into Android's settings feel nothing alike. */
internal fun PermissionKind.actionRes(): Int = when (this) {
    PermissionKind.RUNTIME -> R.string.permission_action_runtime
    PermissionKind.SPECIAL_ACCESS -> R.string.permission_action_special
    PermissionKind.SYSTEM_TOGGLE -> R.string.permission_action_toggle
}

internal fun PermissionStatus.labelRes(): Int = when (this) {
    PermissionStatus.GRANTED -> R.string.permission_status_granted
    PermissionStatus.DENIED -> R.string.permission_status_denied
    PermissionStatus.UNSUPPORTED -> R.string.permission_status_unsupported
}

internal fun PermissionId.titleRes(): Int = when (this) {
    PermissionId.BLUETOOTH -> R.string.permission_bluetooth_title
    PermissionId.BLUETOOTH_ENABLED -> R.string.permission_bluetooth_enabled_title
    PermissionId.NOTIFICATION_ACCESS -> R.string.permission_notification_access_title
    PermissionId.BATTERY_UNRESTRICTED -> R.string.permission_battery_title
    PermissionId.DRAW_OVERLAYS -> R.string.permission_overlay_title
    PermissionId.NOTIFICATIONS -> R.string.permission_notifications_title
    PermissionId.LOCATION_WEATHER -> R.string.permission_location_weather_title
    PermissionId.LOCATION_SERVICES -> R.string.permission_location_services_title
    PermissionId.MICROPHONE -> R.string.permission_microphone_title
    PermissionId.CONTACTS -> R.string.permission_contacts_title
    PermissionId.PLACE_CALLS -> R.string.permission_place_calls_title
    PermissionId.ANSWER_CALLS -> R.string.permission_answer_calls_title
    PermissionId.SEND_SMS -> R.string.permission_send_sms_title
}

internal fun PermissionId.descriptionRes(): Int = when (this) {
    PermissionId.BLUETOOTH -> R.string.permission_bluetooth_desc
    PermissionId.BLUETOOTH_ENABLED -> R.string.permission_bluetooth_enabled_desc
    PermissionId.NOTIFICATION_ACCESS -> R.string.permission_notification_access_desc
    PermissionId.BATTERY_UNRESTRICTED -> R.string.permission_battery_desc
    PermissionId.DRAW_OVERLAYS -> R.string.permission_overlay_desc
    PermissionId.NOTIFICATIONS -> R.string.permission_notifications_desc
    PermissionId.LOCATION_WEATHER -> R.string.permission_location_weather_desc
    PermissionId.LOCATION_SERVICES -> R.string.permission_location_services_desc
    PermissionId.MICROPHONE -> R.string.permission_microphone_desc
    PermissionId.CONTACTS -> R.string.permission_contacts_desc
    PermissionId.PLACE_CALLS -> R.string.permission_place_calls_desc
    PermissionId.ANSWER_CALLS -> R.string.permission_answer_calls_desc
    PermissionId.SEND_SMS -> R.string.permission_send_sms_desc
}
