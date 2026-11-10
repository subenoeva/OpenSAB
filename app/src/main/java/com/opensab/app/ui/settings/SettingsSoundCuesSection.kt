package com.opensab.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opensab.app.R
import com.opensab.domain.settings.AppSettings

@Composable
internal fun SoundCuesSection(
    settings: AppSettings,
    onIntent: (SettingsUiIntent) -> Unit
) {
    SettingsCategory(
        title = stringResource(R.string.settings_category_sound_cues),
        icon = Icons.Default.NotificationsActive
    ) {
        SettingsSegmentedTabSelector(
            title = stringResource(R.string.settings_sound_cues_title),
            subtitle = stringResource(R.string.settings_sound_cues_desc),
            options = listOf(false, true),
            selected = settings.soundCuesEnabled,
            optionLabel = { enabled ->
                if (enabled) stringResource(R.string.settings_mode_on)
                else stringResource(R.string.settings_mode_off)
            },
            optionIcon = { enabled ->
                if (enabled) Icons.Default.NotificationsActive
                else Icons.Default.NotificationsOff
            },
            onSelect = { enabled ->
                onIntent(SettingsUiIntent.UpdateSettings {
                    it.copy(soundCuesEnabled = enabled)
                })
            }
        )
    }
}
