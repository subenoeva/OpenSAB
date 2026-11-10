package com.opensab.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opensab.app.R
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.Tunable

@Composable
internal fun ConnectivitySection(
    settings: AppSettings,
    onIntent: (SettingsUiIntent) -> Unit,
    onResetKeys: () -> Unit,
    onEditTunable: (Tunable) -> Unit
) {
    SettingsCategory(
        title = stringResource(R.string.settings_category_connectivity),
        icon = Icons.Default.Bluetooth
    ) {
        SettingsSwitchItem(
            title = stringResource(R.string.settings_auto_connect_title),
            subtitle = stringResource(R.string.settings_auto_connect_desc),
            checked = settings.autoConnect,
            onCheckedChange = { checked ->
                onIntent(SettingsUiIntent.UpdateSettings { it.copy(autoConnect = checked) })
            }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.settings_auto_disconnect_title),
            subtitle = stringResource(R.string.settings_auto_disconnect_desc),
            checked = settings.autoDisconnect,
            onCheckedChange = { checked ->
                onIntent(SettingsUiIntent.UpdateSettings { it.copy(autoDisconnect = checked) })
            }
        )
        SettingsTunableItem(Tunable.SAB_MODE_WAIT, settings, onEditTunable)
        SettingsActionItem(
            title = stringResource(R.string.settings_reset_keys_title),
            subtitle = stringResource(R.string.settings_reset_keys_desc),
            icon = Icons.Default.Key,
            onClick = onResetKeys
        )
    }
}

@Composable
internal fun ResetSessionKeysDialog(
    onIntent: (SettingsUiIntent) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.dialog_reset_keys_title)) },
        text = { Text(stringResource(R.string.dialog_reset_keys_desc)) },
        confirmButton = {
            Button(
                onClick = {
                    onIntent(SettingsUiIntent.ResetSessionKeys)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.dialog_action_reset_keys))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
            }) { Text(stringResource(R.string.dialog_action_cancel)) }
        }
    )
}
