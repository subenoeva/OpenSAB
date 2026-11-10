package com.opensab.app.ui.permissions

/** The order of this enum is not the order of the screen: see [PermissionCatalog.entriesFor]. */
enum class PermissionId {
    BLUETOOTH,
    NOTIFICATIONS,
    NOTIFICATION_ACCESS,
    BATTERY_UNRESTRICTED,
    DRAW_OVERLAYS,
    LOCATION_WEATHER,
    MICROPHONE,
    CONTACTS,
    PLACE_CALLS,
    ANSWER_CALLS,
    SEND_SMS,
    BLUETOOTH_ENABLED,
    LOCATION_SERVICES
}

enum class PermissionKind {

    RUNTIME,

    /** A system screen the rider has to walk into: notification access, battery exemption. */
    SPECIAL_ACCESS,

    /** A switch nobody can flip on the rider's behalf: the radio, the location services. */
    SYSTEM_TOGGLE
}

/** Where a row stands on this phone. */
enum class PermissionStatus {
    GRANTED,
    DENIED,

    /** This phone cannot. Not a failure: the row is painted, and is not clickable. */
    UNSUPPORTED
}

/**
 * [permissions] is empty outside [PermissionKind.RUNTIME] on purpose: special access and system
 * toggles are not asked for with a dialog; [SystemScreenPolicy] picks the screen that opens them.
 */
data class PermissionEntry(
    val id: PermissionId,
    val kind: PermissionKind,
    val essential: Boolean,
    val permissions: List<String> = emptyList()
)

/**
 * A pure function of the API level, with no `android.*` import, so it runs in JVM tests.
 *
 * Deliberately **no** default for [entriesFor]'s argument: a `Build.VERSION.SDK_INT` default reads
 * `0` in every JVM test and would silently drop every gated row. The API level comes from
 * `PermissionProbe.sdkInt`.
 */
object PermissionCatalog {

    private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    private const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    private const val ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    private const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    private const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    private const val CALL_PHONE = "android.permission.CALL_PHONE"
    private const val ANSWER_PHONE_CALLS = "android.permission.ANSWER_PHONE_CALLS"
    private const val SEND_SMS = "android.permission.SEND_SMS"

    /** `Build.VERSION_CODES.TIRAMISU`, spelled out so this file stays free of Android. */
    private const val ANDROID_13 = 33

    /** `Build.VERSION_CODES.S`. */
    private const val ANDROID_12 = 31

    /**
     * In painting order: first what stops the connection, then what holds up the most features. A
     * row whose API level has not arrived is not painted at all, unlike
     * [PermissionStatus.UNSUPPORTED], where the rider is expecting something that will not happen.
     */
    fun entriesFor(sdkInt: Int): List<PermissionEntry> = buildList {
        add(
            PermissionEntry(
                id = PermissionId.BLUETOOTH,
                kind = PermissionKind.RUNTIME,
                essential = true,
                permissions = if (sdkInt >= ANDROID_12) {
                    listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT)
                } else {
                    listOf(ACCESS_FINE_LOCATION)
                }
            )
        )
        add(
            PermissionEntry(
                id = PermissionId.BLUETOOTH_ENABLED,
                kind = PermissionKind.SYSTEM_TOGGLE,
                essential = true
            )
        )
        add(
            PermissionEntry(
                id = PermissionId.NOTIFICATION_ACCESS,
                kind = PermissionKind.SPECIAL_ACCESS,
                essential = false
            )
        )
        add(
            PermissionEntry(
                id = PermissionId.BATTERY_UNRESTRICTED,
                kind = PermissionKind.SPECIAL_ACCESS,
                essential = false
            )
        )
        add(
            PermissionEntry(
                id = PermissionId.DRAW_OVERLAYS,
                kind = PermissionKind.SPECIAL_ACCESS,
                essential = false
            )
        )
        if (sdkInt >= ANDROID_13) {
            add(
                PermissionEntry(
                    id = PermissionId.NOTIFICATIONS,
                    kind = PermissionKind.RUNTIME,
                    essential = false,
                    permissions = listOf(POST_NOTIFICATIONS)
                )
            )
        }
        add(
            PermissionEntry(
                id = PermissionId.LOCATION_WEATHER,
                kind = PermissionKind.RUNTIME,
                essential = false,
                permissions = listOf(ACCESS_COARSE_LOCATION)
            )
        )
        add(
            PermissionEntry(
                id = PermissionId.LOCATION_SERVICES,
                kind = PermissionKind.SYSTEM_TOGGLE,
                essential = false
            )
        )
        add(
            PermissionEntry(
                id = PermissionId.MICROPHONE,
                kind = PermissionKind.RUNTIME,
                essential = false,
                permissions = listOf(RECORD_AUDIO)
            )
        )
        add(
            PermissionEntry(
                id = PermissionId.CONTACTS,
                kind = PermissionKind.RUNTIME,
                essential = false,
                permissions = listOf(READ_CONTACTS)
            )
        )
        add(
            PermissionEntry(
                id = PermissionId.PLACE_CALLS,
                kind = PermissionKind.RUNTIME,
                essential = false,
                permissions = listOf(CALL_PHONE)
            )
        )
        // The UNSUPPORTED below API 31 belongs here, not to NOTIFICATION_ACCESS: without
        // `Notification$CallStyle` granting ANSWER_PHONE_CALLS buys nothing, while notification
        // access still holds up music, messages and turn-by-turn.
        add(
            PermissionEntry(
                id = PermissionId.ANSWER_CALLS,
                kind = PermissionKind.RUNTIME,
                essential = false,
                permissions = listOf(ANSWER_PHONE_CALLS)
            )
        )
        add(
            PermissionEntry(
                id = PermissionId.SEND_SMS,
                kind = PermissionKind.RUNTIME,
                essential = false,
                permissions = listOf(SEND_SMS)
            )
        )
    }
}
