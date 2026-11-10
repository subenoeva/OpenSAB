package com.opensab.app.ui.sessions

import com.opensab.logging.journal.ExportRequest
import com.opensab.logging.journal.SessionListing
import java.io.File

/** The session list and, when [detail] is set, one session's detail on top of it. */
data class SessionsUiState(
    val isLoading: Boolean = true,
    val listings: List<SessionListing> = emptyList(),
    val onlyAttention: Boolean = false,
    /** Keyed by the first base name of the run. */
    val expandedAttempts: Set<String> = emptySet(),
    /** Chosen by long press; insertion order is the export order. */
    val selected: Set<String> = emptySet(),
    val detail: SessionListing? = null,
    /** Set while the privacy notice is showing; nothing is exported until it is confirmed. */
    val pendingExport: ExportRequest? = null,
) {
    val rows: List<SessionRow> get() = sessionRows(listings, onlyAttention, expandedAttempts)
}

sealed interface SessionsUiIntent {
    data object Refresh : SessionsUiIntent
    data class SetOnlyAttention(val on: Boolean) : SessionsUiIntent
    data class ToggleAttempts(val key: String) : SessionsUiIntent
    data class Open(val baseName: String) : SessionsUiIntent
    data object CloseDetail : SessionsUiIntent
    data class ToggleSelected(val baseName: String) : SessionsUiIntent
    data object ShareSelected : SessionsUiIntent
    data object ShareDetail : SessionsUiIntent
    data object ShareAll : SessionsUiIntent
    data object ConfirmExport : SessionsUiIntent
    data object CancelExport : SessionsUiIntent
    data object NavigateBack : SessionsUiIntent
}

sealed interface SessionsUiEvent {
    data class Share(val zip: File) : SessionsUiEvent
    data object ExportFailed : SessionsUiEvent
    data object NavigateBack : SessionsUiEvent
}
