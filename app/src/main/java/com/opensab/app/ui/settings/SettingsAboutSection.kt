package com.opensab.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opensab.app.R
import com.opensab.domain.settings.AppSettings

@Composable
internal fun AboutSection(
    settings: AppSettings,
    verboseLogging: Boolean,
    onIntent: (SettingsUiIntent) -> Unit,
    onResetDefaults: () -> Unit
) {
    SettingsCategory(
        title = stringResource(R.string.settings_category_about),
        icon = Icons.Default.Info
    ) {
        SettingsSwitchItem(
            title = stringResource(R.string.settings_verbose_logging_title),
            subtitle = stringResource(R.string.settings_verbose_logging_desc),
            checked = verboseLogging,
            onCheckedChange = { checked ->
                onIntent(SettingsUiIntent.UpdateSettings { it.copy(verboseLogging = checked) })
            }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.settings_sab_mode_rerequest_title),
            subtitle = stringResource(R.string.settings_sab_mode_rerequest_desc),
            checked = settings.sabModeRerequestOnHuMode,
            onCheckedChange = { checked ->
                onIntent(SettingsUiIntent.UpdateSettings { it.copy(sabModeRerequestOnHuMode = checked) })
            }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.settings_project_without_grant_title),
            subtitle = stringResource(R.string.settings_project_without_grant_desc),
            checked = settings.projectWithoutSabModeGrant,
            onCheckedChange = { checked ->
                onIntent(SettingsUiIntent.UpdateSettings { it.copy(projectWithoutSabModeGrant = checked) })
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    stringResource(R.string.settings_app_version_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.settings_app_version_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                stringResource(R.string.settings_clean_room_badge),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF4CAF50)
            )
        }
        SettingsActionItem(
            title = stringResource(R.string.settings_reset_all_title),
            subtitle = stringResource(R.string.settings_reset_all_desc),
            icon = Icons.Default.Restore,
            isDestructive = true,
            onClick = onResetDefaults
        )
    }
}

@Composable
internal fun ResetDefaultsDialog(
    onIntent: (SettingsUiIntent) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.dialog_reset_defaults_title)) },
        text = { Text(stringResource(R.string.dialog_reset_defaults_desc)) },
        confirmButton = {
            Button(
                onClick = {
                    onIntent(SettingsUiIntent.ResetSettingsToDefaults)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.dialog_action_reset_all))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
            }) { Text(stringResource(R.string.dialog_action_cancel)) }
        }
    )
}
