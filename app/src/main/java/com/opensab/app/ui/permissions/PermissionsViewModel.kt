package com.opensab.app.ui.permissions

import com.opensab.app.ui.base.BaseViewModel
import com.opensab.domain.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import timber.log.Timber

/**
 * The one screen of the app that asks for a permission. It never blocks: "Continue" is always
 * enabled, whatever is still denied.
 *
 * @param isIntro the automatic first-run showing. It arrives by assisted injection rather than from
 *   a later DataStore read so the **first** state already has the right footer; anything
 *   asynchronous would paint a back arrow for one frame and then swap it for "Continue".
 */
@HiltViewModel(assistedFactory = PermissionsViewModel.Factory::class)
class PermissionsViewModel @AssistedInject constructor(
    @Assisted isIntro: Boolean,
    private val entries: List<PermissionEntry>,
    private val statusReader: PermissionStatusReader,
    private val settingsRepository: SettingsRepository
) : BaseViewModel<PermissionsUiState, PermissionsUiIntent, PermissionsUiEvent>(
    PermissionsUiState(isIntro = isIntro)
) {

    /**
     * The intro flag is owned by this ViewModel and written once, on the press, not on the paint: a
     * double tap must not write twice, and a first run killed from the task switcher has to come
     * back.
     */
    private var continued = false

    init {
        refresh()
    }

    override suspend fun handleIntent(intent: PermissionsUiIntent) {
        when (intent) {
            is PermissionsUiIntent.Refresh -> refresh()
            is PermissionsUiIntent.Grant -> grant(intent.id)
            is PermissionsUiIntent.Continue -> continueToScan()
            is PermissionsUiIntent.NavigateBack -> sendEvent(PermissionsUiEvent.NavigateBack)
        }
    }

    /**
     * `statusOf` does not suspend, so this runs from `init` too, before there is a collector, and the
     * first state the screen sees is already true.
     */
    private fun refresh() {
        updateState { copy(rows = entries.map { PermissionRow(it, statusReader.statusOf(it)) }) }
    }

    /**
     * Nothing happens for an `UNSUPPORTED` row. The composable paints it disabled, and this does not
     * take the composable's word for it.
     */
    private fun grant(id: PermissionId) {
        val row = currentState.rows.firstOrNull { it.entry.id == id } ?: return
        if (row.status == PermissionStatus.UNSUPPORTED) return

        when (row.entry.kind) {
            PermissionKind.RUNTIME ->
                sendEvent(PermissionsUiEvent.RequestRuntime(row.entry.permissions))
            PermissionKind.SPECIAL_ACCESS, PermissionKind.SYSTEM_TOGGLE ->
                sendEvent(PermissionsUiEvent.OpenSystemScreen(id))
        }
    }

    private suspend fun continueToScan() {
        if (currentState.isIntro && !continued) {
            continued = true
            settingsRepository.updateSettings { it.copy(permissionsIntroSeen = true) }
            Timber.i("Permissions intro seen; it will not open itself again")
        }
        sendEvent(PermissionsUiEvent.NavigateToScan)
    }

    @AssistedFactory
    interface Factory {
        fun create(isIntro: Boolean): PermissionsViewModel
    }
}
