package com.opensab.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opensab.app.R
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.InboundCallMode

@Composable
internal fun CallsSection(
    settings: AppSettings,
    onIntent: (SettingsUiIntent) -> Unit
) {
    SettingsCategory(
        title = stringResource(R.string.settings_category_calls),
        icon = Icons.Default.Call
    ) {
        SettingsSegmentedTabSelector(
            title = stringResource(R.string.settings_inbound_calls_title),
            subtitle = when (settings.inboundCallMode) {
                InboundCallMode.ALERT -> stringResource(R.string.settings_inbound_calls_alert_desc)
                InboundCallMode.IGNORE -> stringResource(R.string.settings_inbound_calls_ignore_desc)
            },
            options = InboundCallMode.entries,
            selected = settings.inboundCallMode,
            optionLabel = { mode ->
                when (mode) {
                    InboundCallMode.ALERT -> stringResource(R.string.settings_inbound_calls_alert)
                    InboundCallMode.IGNORE -> stringResource(R.string.settings_inbound_calls_ignore)
                }
            },
            optionIcon = { mode ->
                when (mode) {
                    InboundCallMode.ALERT -> Icons.Default.Call
                    InboundCallMode.IGNORE -> Icons.Default.NotificationsOff
                }
            },
            onSelect = { mode ->
                onIntent(SettingsUiIntent.UpdateSettings {
                    it.copy(inboundCallMode = mode)
                })
            }
        )
    }
}
