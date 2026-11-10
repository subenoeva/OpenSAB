package com.opensab.app.ui.settings.rootmenu

import androidx.lifecycle.viewModelScope
import com.opensab.app.ui.base.BaseViewModel
import com.opensab.domain.settings.RootMenuRow
import com.opensab.domain.settings.SettingsRepository
import com.opensab.domain.settings.moved
import com.opensab.domain.settings.resolveRootMenu
import com.opensab.domain.settings.toggled
import com.opensab.domain.settings.withRootMenu
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Every change is applied to what is stored, not to what this screen last saw, inside the
 * repository's own transaction: the screen can never write back a layout older than the one on
 * disk. The rows are updated first so a dropped row does not jump back while DataStore answers.
 */
@HiltViewModel
class RootMenuSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : BaseViewModel<RootMenuSettingsUiState, RootMenuSettingsUiIntent, RootMenuSettingsUiEvent>(
    RootMenuSettingsUiState()
) {

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.whileSubscribed().collect { settings ->
                updateState { copy(rows = resolveRootMenu(settings.rootMenuOrder, settings.rootMenuHidden)) }
            }
        }
    }

    override suspend fun handleIntent(intent: RootMenuSettingsUiIntent) {
        when (intent) {
            is RootMenuSettingsUiIntent.Move -> rearrange { it.moved(intent.from, intent.to) }
            is RootMenuSettingsUiIntent.Toggle -> rearrange { it.toggled(intent.entry) }
            RootMenuSettingsUiIntent.Reset -> settingsRepository.updateSettings {
                it.copy(rootMenuOrder = emptyList(), rootMenuHidden = emptySet())
            }
            RootMenuSettingsUiIntent.NavigateBack -> sendEvent(RootMenuSettingsUiEvent.NavigateBack)
        }
    }

    private suspend fun rearrange(change: (List<RootMenuRow>) -> List<RootMenuRow>) {
        updateState { copy(rows = change(rows)) }
        settingsRepository.updateSettings { settings ->
            val stored = resolveRootMenu(settings.rootMenuOrder, settings.rootMenuHidden)
            val changed = change(stored)
            if (changed == stored) settings else settings.withRootMenu(changed)
        }
    }
}
