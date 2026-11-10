package com.opensab.app.ui.settings.rootmenu

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.opensab.domain.settings.resolveRootMenu

class RootMenuSettingsScreenPreviewProvider : PreviewParameterProvider<RootMenuSettingsUiState> {
    override val values: Sequence<RootMenuSettingsUiState> = sequenceOf(
        // Scenario 1: Untouched, the factory order with every entry shown
        RootMenuSettingsUiState(),
        // Scenario 2: Rearranged, music first and two entries hidden
        RootMenuSettingsUiState(
            rows = resolveRootMenu(
                order = listOf("MUSIC", "NAVIGATION", "CALL", "WEATHER", "MESSAGE", "VOLUME"),
                hidden = setOf("WEATHER", "VOLUME")
            )
        ),
        // Scenario 3: One entry left, its checkbox locked so the carousel is never empty
        RootMenuSettingsUiState(
            rows = resolveRootMenu(
                order = listOf("NAVIGATION"),
                hidden = setOf("WEATHER", "CALL", "MESSAGE", "MUSIC", "VOLUME")
            )
        )
    )
}
