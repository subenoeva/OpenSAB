package com.opensab.app.ui.sessions

import androidx.lifecycle.viewModelScope
import com.opensab.app.logging.JournalListing
import com.opensab.app.ui.base.BaseViewModel
import com.opensab.logging.journal.ExportRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The list and its detail are one state (back closes the detail rather than popping a route). The
 * journal answers from its own thread; [JournalListing] brings the answer back to this scope before
 * any state is touched.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val journal: JournalListing
) : BaseViewModel<SessionsUiState, SessionsUiIntent, SessionsUiEvent>(SessionsUiState()) {

    init {
        viewModelScope.launch { refresh() }
    }

    override suspend fun handleIntent(intent: SessionsUiIntent) {
        when (intent) {
            SessionsUiIntent.Refresh -> refresh()
            SessionsUiIntent.CloseDetail -> {
                updateState { reduce(intent) }
                refresh()
            }
            SessionsUiIntent.ConfirmExport -> confirmExport()
            SessionsUiIntent.NavigateBack -> sendEvent(SessionsUiEvent.NavigateBack)
            else -> updateState { reduce(intent) }
        }
    }

    private suspend fun refresh() {
        val listings = journal.list()
        updateState { copy(isLoading = false, listings = listings) }
    }

    private suspend fun confirmExport() {
        val request = currentState.pendingExport ?: return
        updateState { copy(pendingExport = null) }
        journal.export(request).fold(
            onSuccess = { sendEvent(SessionsUiEvent.Share(it)) },
            onFailure = { sendEvent(SessionsUiEvent.ExportFailed) }
        )
    }
}

/** Pure: every intent that only changes what the screen shows. */
internal fun SessionsUiState.reduce(intent: SessionsUiIntent): SessionsUiState = when (intent) {
    is SessionsUiIntent.SetOnlyAttention -> copy(onlyAttention = intent.on)
    is SessionsUiIntent.ToggleAttempts -> copy(expandedAttempts = expandedAttempts.toggled(intent.key))
    is SessionsUiIntent.Open -> listings.find { it.baseName == intent.baseName }?.let { copy(detail = it) } ?: this
    SessionsUiIntent.CloseDetail -> copy(detail = null)
    is SessionsUiIntent.ToggleSelected -> copy(selected = selected.toggled(intent.baseName))
    SessionsUiIntent.ShareSelected ->
        if (selected.isEmpty()) this
        else copy(pendingExport = ExportRequest(selected.toList(), includeDays = false), selected = emptySet())
    SessionsUiIntent.ShareDetail ->
        detail?.let { copy(pendingExport = ExportRequest(listOf(it.baseName), includeDays = false)) } ?: this
    SessionsUiIntent.ShareAll -> copy(
        pendingExport = ExportRequest(listings.filterNot { it.isOpen }.map { it.baseName }, includeDays = true)
    )
    SessionsUiIntent.CancelExport -> copy(pendingExport = null)
    SessionsUiIntent.Refresh, SessionsUiIntent.ConfirmExport, SessionsUiIntent.NavigateBack -> this
}

private fun Set<String>.toggled(key: String): Set<String> = if (key in this) this - key else this + key
