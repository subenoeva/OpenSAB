package com.opensab.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import com.opensab.app.R
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.Tunable
import com.opensab.domain.settings.resolveTemperatureUnit

@Composable
internal fun WeatherSection(
    settings: AppSettings,
    onPickTemperatureUnit: () -> Unit,
    onEditTunable: (Tunable) -> Unit
) {
    SettingsCategory(
        title = stringResource(R.string.settings_category_weather),
        icon = Icons.Default.WbSunny
    ) {
        SettingsValueSelectorItem(
            title = stringResource(R.string.settings_temperature_unit_title),
            // A null preference is the rider never having chosen, so the row shows what
            // the country resolves to rather than a blank.
            value = resolveTemperatureUnit(
                settings.temperatureUnit,
                LocalLocale.current.platformLocale.country
            ).localizedLabel(),
            subtitle = stringResource(R.string.settings_temperature_unit_desc),
            onClick = onPickTemperatureUnit
        )
        SettingsTunableItem(Tunable.WEATHER_REFRESH, settings, onEditTunable)
    }
}
