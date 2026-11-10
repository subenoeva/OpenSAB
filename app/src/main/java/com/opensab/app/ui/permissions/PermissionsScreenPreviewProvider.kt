package com.opensab.app.ui.permissions

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

/**
 * The three states worth looking at: the first run with nothing granted, the same screen reached
 * from Settings with most of it in place, and an Android 11 where telephony cannot be granted at
 * all and the notifications row does not exist.
 */
class PermissionsScreenPreviewProvider : PreviewParameterProvider<PermissionsUiState> {

    override val values: Sequence<PermissionsUiState> = sequenceOf(
        PermissionsUiState(
            rows = rows(sdkInt = 36) { PermissionStatus.DENIED },
            isIntro = true
        ),
        PermissionsUiState(
            rows = rows(sdkInt = 36) { id ->
                if (id == PermissionId.BATTERY_UNRESTRICTED || id == PermissionId.MICROPHONE) {
                    PermissionStatus.DENIED
                } else {
                    PermissionStatus.GRANTED
                }
            },
            isIntro = false
        ),
        PermissionsUiState(
            rows = rows(sdkInt = 30) { id ->
                when (id) {
                    PermissionId.ANSWER_CALLS -> PermissionStatus.UNSUPPORTED
                    PermissionId.BLUETOOTH -> PermissionStatus.GRANTED
                    else -> PermissionStatus.DENIED
                }
            },
            isIntro = false
        )
    )

    private fun rows(
        sdkInt: Int,
        status: (PermissionId) -> PermissionStatus
    ): List<PermissionRow> = PermissionCatalog.entriesFor(sdkInt)
        .map { PermissionRow(entry = it, status = status(it.id)) }
}
