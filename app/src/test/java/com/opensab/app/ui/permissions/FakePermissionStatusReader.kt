package com.opensab.app.ui.permissions

/** A [PermissionStatusReader] a test can move: everything is [fallback] until it is not. */
class FakePermissionStatusReader(
    var statuses: Map<PermissionId, PermissionStatus> = emptyMap(),
    var fallback: PermissionStatus = PermissionStatus.DENIED
) : PermissionStatusReader {

    override fun statusOf(entry: PermissionEntry): PermissionStatus =
        statuses[entry.id] ?: fallback
}
