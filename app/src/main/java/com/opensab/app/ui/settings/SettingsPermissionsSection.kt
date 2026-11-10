package com.opensab.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opensab.app.R

@Composable
internal fun PermissionsSection(
    permissions: PermissionSummary,
    onIntent: (SettingsUiIntent) -> Unit
) {
    SettingsCategory(
        title = stringResource(R.string.settings_category_permissions),
        icon = Icons.Default.Lock
    ) {
        SettingsActionItem(
            title = stringResource(R.string.settings_category_permissions),
            subtitle = stringResource(
                R.string.settings_permissions_summary,
                permissions.granted,
                permissions.total
            ),
            icon = Icons.Default.Lock,
            onClick = { onIntent(SettingsUiIntent.OpenPermissions) }
        )
    }
}
