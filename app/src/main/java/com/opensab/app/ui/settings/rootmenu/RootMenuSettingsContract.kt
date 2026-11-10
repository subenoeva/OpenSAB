package com.opensab.app.ui.settings.rootmenu

import com.opensab.domain.settings.RootMenuEntry
import com.opensab.domain.settings.RootMenuRow
import com.opensab.domain.settings.resolveRootMenu

/** The cluster menu screen: the rows as the rider arranged them. */
data class RootMenuSettingsUiState(
    val rows: List<RootMenuRow> = resolveRootMenu(emptyList(), emptySet())
)

sealed interface RootMenuSettingsUiIntent {
    /** Sent once per gesture, on the drop, never while the finger is down. */
    data class Move(val from: Int, val to: Int) : RootMenuSettingsUiIntent
    data class Toggle(val entry: RootMenuEntry) : RootMenuSettingsUiIntent
    data object Reset : RootMenuSettingsUiIntent
    data object NavigateBack : RootMenuSettingsUiIntent
}

sealed interface RootMenuSettingsUiEvent {
    data object NavigateBack : RootMenuSettingsUiEvent
}
