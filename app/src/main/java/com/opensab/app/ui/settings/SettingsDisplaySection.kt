package com.opensab.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opensab.app.R
import com.opensab.domain.settings.AppSettings

@Composable
internal fun DisplaySection(
    settings: AppSettings,
    onIntent: (SettingsUiIntent) -> Unit,
    onPickTheme: () -> Unit
) {
    SettingsCategory(
        title = stringResource(R.string.settings_category_display),
        icon = Icons.Default.Tv
    ) {
        SettingsSwitchItem(
            title = stringResource(R.string.settings_keep_screen_on_title),
            subtitle = stringResource(R.string.settings_keep_screen_on_desc),
            checked = settings.keepScreenOnWhileRiding,
            onCheckedChange = { checked ->
                onIntent(SettingsUiIntent.UpdateSettings {
                    it.copy(
                        keepScreenOnWhileRiding = checked
                    )
                })
            }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.settings_show_on_lock_screen_title),
            subtitle = stringResource(R.string.settings_show_on_lock_screen_desc),
            checked = settings.showOnLockScreen,
            onCheckedChange = { checked ->
                onIntent(SettingsUiIntent.UpdateSettings { it.copy(showOnLockScreen = checked) })
            }
        )
        val (visibleEntries, totalEntries) = settings.rootMenuCounts()
        SettingsValueSelectorItem(
            title = stringResource(R.string.settings_root_menu_title),
            value = stringResource(
                R.string.settings_root_menu_summary,
                visibleEntries,
                totalEntries
            ),
            subtitle = stringResource(R.string.settings_root_menu_desc),
            onClick = { onIntent(SettingsUiIntent.OpenRootMenu) }
        )
        SettingsValueSelectorItem(
            title = stringResource(R.string.settings_theme_title),
            value = settings.themeMode.localizedLabel(),
            subtitle = stringResource(R.string.settings_theme_desc),
            onClick = onPickTheme
        )
    }
}
