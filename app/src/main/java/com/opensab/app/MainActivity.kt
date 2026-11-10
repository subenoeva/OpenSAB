package com.opensab.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.opensab.app.service.ForegroundPromoter
import com.opensab.app.theme.OpenSABTheme
import com.opensab.app.ui.AppStartViewModel
import com.opensab.app.ui.OpenSabApp
import com.opensab.app.ui.ScreenOnPolicy
import com.opensab.domain.settings.SettingsRepository
import com.opensab.sab.session.SabSessions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** The session state, read from its one owner, `SabSessionController`. */
    @Inject
    lateinit var sabSessions: SabSessions

    @Inject
    lateinit var foregroundPromoter: ForegroundPromoter

    /**
     * Resolved here and not only inside `OpenSabApp`, because the splash needs something to wait
     * on and a composable is not available yet when it has to be told what to wait for.
     *
     * It is the same object either way: `OpenSabApp` is composed outside any `NavEntry`, so its
     * `hiltViewModel()` resolves against this Activity's `ViewModelStore` and finds this instance.
     * DataStore is read once, by whichever of the two touches it first.
     */
    private val startViewModel: AppStartViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: this is what swaps the launch theme for postSplashScreenTheme, and
        // it has to happen before the window is created to have anything to swap.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // The frame in which the start route is not known yet is left blank rather than filled
        // with a guess. The splash covers it: no NavDisplay is composed until DataStore has
        // replied, and nothing flashes in between.
        splashScreen.setKeepOnScreenCondition { startViewModel.startRoute.value == null }

        // From onCreate, not STARTED: over the keyguard the activity only reaches STARTED once
        // this flag is set.
        lifecycleScope.launch {
            settingsRepository.settingsFlow
                .map(ScreenOnPolicy::showWhenLocked)
                .distinctUntilChanged()
                .collect { show ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        setShowWhenLocked(show)
                    } else if (show) {
                        @Suppress("DEPRECATION")
                        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                    } else {
                        @Suppress("DEPRECATION")
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                    }
                }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        settingsRepository.settingsFlow,
                        sabSessions.state
                    ) { settings, sessionState ->
                        ScreenOnPolicy.shouldKeepScreenOn(settings, sessionState)
                    }.collect { shouldKeepOn ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            setTurnScreenOn(shouldKeepOn)
                        } else if (shouldKeepOn) {
                            @Suppress("DEPRECATION")
                            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                        } else {
                            @Suppress("DEPRECATION")
                            window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                        }
                        if (shouldKeepOn) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                }
            }
        }

        enableEdgeToEdge()

        // The permissions footer reaches the bottom edge itself. enableEdgeToEdge turns the scrim
        // on, and left on, the system paints a translucent band over three-button navigation and
        // the footer appears to stop short of the edge.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            OpenSABTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // No permission wall here. The app opens, and the one screen that asks is a
                    // route it navigates to.
                    //
                    // And no safeDrawingPadding either: consuming the insets this high up keeps
                    // the app from ever drawing under the system bars. Every screen below
                    // takes the insets it needs, and decides which of them it wants to scroll
                    // content under.
                    OpenSabApp()
                }
            }
        }
    }

    /**
     * Resumed is the moment Android counts the app as visible, and the only one in which a service
     * started from the ACL receiver can be handed the microphone it was refused.
     */
    override fun onResume() {
        super.onResume()
        foregroundPromoter.onUiVisible()
    }
}
