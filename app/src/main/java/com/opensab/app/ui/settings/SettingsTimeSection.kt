package com.opensab.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opensab.app.R
import com.opensab.domain.settings.AppSettings

@Composable
internal fun TimeSection(
    settings: AppSettings,
    onIntent: (SettingsUiIntent) -> Unit
) {
    SettingsCategory(
        title = stringResource(R.string.settings_category_time),
        icon = Icons.Default.Schedule
    ) {
        SettingsSwitchItem(
            title = stringResource(R.string.settings_cts_sync_title),
            subtitle = stringResource(R.string.settings_cts_sync_desc),
            checked = settings.ctsClockSync,
            onCheckedChange = { checked ->
                onIntent(SettingsUiIntent.UpdateSettings { it.copy(ctsClockSync = checked) })
            }
        )
    }
}
