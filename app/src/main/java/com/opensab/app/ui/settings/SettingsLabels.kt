package com.opensab.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opensab.app.R
import com.opensab.domain.settings.TemperatureUnit
import com.opensab.domain.settings.ThemeMode
import com.opensab.domain.settings.VoiceVerbosity

@Composable
internal fun TemperatureUnit.localizedLabel(): String = when (this) {
    TemperatureUnit.CELSIUS -> stringResource(R.string.unit_celsius_label)
    TemperatureUnit.FAHRENHEIT -> stringResource(R.string.unit_fahrenheit_label)
}

@Composable
internal fun ThemeMode.localizedLabel(): String = when (this) {
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system_label)
    ThemeMode.DARK -> stringResource(R.string.theme_dark_label)
    ThemeMode.LIGHT -> stringResource(R.string.theme_light_label)
}

@Composable
internal fun VoiceVerbosity.localizedLabel(): String = when (this) {
    VoiceVerbosity.NONE -> stringResource(R.string.settings_voice_verbosity_none_label)
    VoiceVerbosity.STANDARD -> stringResource(R.string.settings_voice_verbosity_standard_label)
    VoiceVerbosity.ADVANCED -> stringResource(R.string.settings_voice_verbosity_advanced_label)
}

@Composable
internal fun VoiceVerbosity.localizedDescription(): String = when (this) {
    VoiceVerbosity.NONE -> stringResource(R.string.settings_voice_verbosity_none_desc)
    VoiceVerbosity.STANDARD -> stringResource(R.string.settings_voice_verbosity_standard_desc)
    VoiceVerbosity.ADVANCED -> stringResource(R.string.settings_voice_verbosity_advanced_desc)
}
