package com.opensab.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opensab.app.R
import com.opensab.domain.settings.AppSettings

@Composable
internal fun NavigationSection(
    settings: AppSettings,
    onIntent: (SettingsUiIntent) -> Unit
) {
    SettingsCategory(
        title = stringResource(R.string.settings_category_navigation),
        icon = Icons.Default.Directions
    ) {
        SettingsSwitchItem(
            title = stringResource(R.string.settings_intercept_gps_title),
            subtitle = stringResource(R.string.settings_intercept_gps_desc),
            checked = settings.interceptGpsNotifications,
            onCheckedChange = { checked ->
                onIntent(SettingsUiIntent.UpdateSettings {
                    it.copy(
                        interceptGpsNotifications = checked
                    )
                })
            }
        )
    }
}
