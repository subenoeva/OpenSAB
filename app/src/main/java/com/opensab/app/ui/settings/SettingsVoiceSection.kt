package com.opensab.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.VoiceOverOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opensab.app.R
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.VoiceVerbosity

@Composable
internal fun VoiceSection(
    settings: AppSettings,
    onIntent: (SettingsUiIntent) -> Unit
) {
    SettingsCategory(
        title = stringResource(R.string.settings_category_voice),
        icon = Icons.Default.RecordVoiceOver
    ) {
        SettingsSegmentedTabSelector(
            title = stringResource(R.string.settings_voice_verbosity_title),
            subtitle = settings.voiceVerbosity.localizedDescription(),
            options = VoiceVerbosity.entries,
            selected = settings.voiceVerbosity,
            optionLabel = { it.localizedLabel() },
            optionIcon = { verbosity ->
                when (verbosity) {
                    VoiceVerbosity.NONE -> Icons.Default.VoiceOverOff
                    VoiceVerbosity.STANDARD -> Icons.Default.RecordVoiceOver
                    VoiceVerbosity.ADVANCED -> Icons.Default.SupportAgent
                }
            },
            onSelect = { mode ->
                onIntent(SettingsUiIntent.UpdateSettings {
                    it.copy(voiceVerbosity = mode)
                })
            }
        )
    }
}
