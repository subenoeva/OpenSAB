package com.opensab.app.ui.permissions

/** One row of the screen: what the catalogue says it is, and where it stands on this phone. */
data class PermissionRow(
    val entry: PermissionEntry,
    val status: PermissionStatus
)

data class PermissionsUiState(
    val rows: List<PermissionRow> = emptyList(),

    /** First run: the footer is "Continue". From Settings: a back arrow and no footer at all. */
    val isIntro: Boolean = false
)

sealed interface PermissionsUiIntent {

    /** Re-reads every row. Sent on ON_RESUME and by each launcher on its way back. */
    data object Refresh : PermissionsUiIntent

    data class Grant(val id: PermissionId) : PermissionsUiIntent

    data object Continue : PermissionsUiIntent

    data object NavigateBack : PermissionsUiIntent
}

sealed interface PermissionsUiEvent {

    /** Launches the system dialog. The launcher lives in the composable; the permission, here. */
    data class RequestRuntime(val permissions: List<String>) : PermissionsUiEvent

    /** Opens the system screen a special access or a system toggle is granted from. */
    data class OpenSystemScreen(val id: PermissionId) : PermissionsUiEvent

    data object NavigateToScan : PermissionsUiEvent

    data object NavigateBack : PermissionsUiEvent
}
