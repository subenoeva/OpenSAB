package com.opensab.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensab.app.ui.navigation.AppRoute
import com.opensab.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Which screen the app opens on. Not a `BaseViewModel`: no intents, no events, no screen.
 *
 * The flag is read **once per process and never observed**: observing it would mean that pressing
 * "Continue", which writes it, moved the rider off the screen he is standing on.
 */
@HiltViewModel
class AppStartViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _startRoute = MutableStateFlow<AppRoute?>(null)

    /**
     * `null` means "I do not know yet", and it is the honest first answer: reading DataStore is
     * asynchronous. `OpenSabApp` paints the theme's background for that frame instead of composing
     * `NavDisplay` with a guess and correcting it, which would flash the scan screen on every
     * first run.
     */
    val startRoute: StateFlow<AppRoute?> = _startRoute.asStateFlow()

    init {
        viewModelScope.launch {
            val introSeen = settingsRepository.getSettings().permissionsIntroSeen
            _startRoute.value = if (introSeen) {
                AppRoute.Scan
            } else {
                AppRoute.Permissions(isIntro = true)
            }
        }
    }
}
