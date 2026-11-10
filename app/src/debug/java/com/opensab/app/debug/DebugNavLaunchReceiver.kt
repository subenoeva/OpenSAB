package com.opensab.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opensab.domain.nav.DeviceUnlockSource
import com.opensab.domain.nav.GuidanceAwaiter
import com.opensab.domain.nav.NavRelaunchPolicy
import com.opensab.domain.nav.NavigationLaunch
import com.opensab.domain.nav.NavigationLauncher
import com.opensab.domain.nav.NavigationNotificationBus
import com.opensab.logging.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a voice search's launch without a motorcycle: the production launcher and unlock source,
 * and [GuidanceAwaiter] fed by the notification bus, since `NavigationManager` only runs in a
 * session. Logs what the page would do; draws nothing.
 *
 * ```
 * adb shell am broadcast -p com.opensab -a com.opensab.debug.NAV_LAUNCH --es query "Santiago de Compostela"
 * adb shell am broadcast -p com.opensab -a com.opensab.debug.NAV_BACK
 * ```
 *
 * `NAV_BACK` cancels the run, as the engine cancels the work of a page it pops.
 * **Owner of the run: this class's companion**, one at a time; a new launch cancels the previous.
 */
@AndroidEntryPoint
class DebugNavLaunchReceiver : BroadcastReceiver() {

    @Inject
    lateinit var launcher: NavigationLauncher

    @Inject
    lateinit var unlockSource: DeviceUnlockSource

    @Inject
    lateinit var navigationBus: NavigationNotificationBus

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_NAV_LAUNCH -> start(intent.getStringExtra(EXTRA_QUERY) ?: return)
            ACTION_NAV_BACK -> {
                Log.i("DebugNavLaunch: BACK, run cancelled")
                run?.cancel()
            }
        }
    }

    private fun start(query: String) {
        run?.cancel()
        run = scope.launch {
            val instructions = navigationBus.instructions.stateIn(this, SharingStarted.Eagerly, null)
            val launch = launcher.launchNavigation(query)
            Log.i("DebugNavLaunch: launched '%s': %s", query, launch)
            if (launch !is NavigationLaunch.Started) return@launch

            val awaiter = GuidanceAwaiter(launcher, unlockSource, instructions)
            val guidance = async { awaiter.await(query, NavRelaunchPolicy(baseline = instructions.value)) }
            if (withTimeoutOrNull(PAGE_WATCHDOG) { guidance.await() } == null) {
                Log.i("DebugNavLaunch: watchdog, face=%s", if (launch.deviceLocked) "UnlockPhone" else "AmbiguousQuery")
            }
            Log.i("DebugNavLaunch: guidance started: %s", guidance.await().title)
            coroutineContext.cancelChildren()
        }
    }

    companion object {
        const val ACTION_NAV_LAUNCH = "com.opensab.debug.NAV_LAUNCH"
        const val ACTION_NAV_BACK = "com.opensab.debug.NAV_BACK"
        const val EXTRA_QUERY = "query"

        /** The voice search page's watchdog. */
        private val PAGE_WATCHDOG = 15.seconds

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var run: Job? = null
    }
}
