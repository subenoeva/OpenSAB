package com.opensab.domain.nav

import com.opensab.logging.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch

/**
 * Waits for the guidance a launched route starts, re-sending the launch on each unlock as
 * [NavRelaunchPolicy] allows.
 *
 * Listens for unlocks only inside [await]: once guidance arrives or the caller is cancelled, an
 * unlock re-sends nothing.
 */
class GuidanceAwaiter(
    private val launcher: NavigationLauncher,
    private val unlockSource: DeviceUnlockSource,
    private val instructions: StateFlow<NavInstruction?>
) {

    suspend fun await(query: String, policy: NavRelaunchPolicy): NavInstruction = coroutineScope {
        val relauncher = launch {
            unlockSource.unlocks.fold(policy) { current, _ ->
                if (!current.mayRelaunch) return@fold current
                Log.i("Phone unlocked before guidance started; re-sending the route")
                launcher.launchNavigation(query)
                current.relaunched()
            }
        }
        instructions.filterNotNull().first { policy.startsGuidance(it) }.also { relauncher.cancel() }
    }
}
