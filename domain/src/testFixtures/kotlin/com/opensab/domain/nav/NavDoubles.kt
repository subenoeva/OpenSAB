package com.opensab.domain.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [NavigationManager] double that records the one action a page can ask of it.
 *
 * **Owner: the mirror contract harness.**
 *
 * `updateInstruction` is state setup driven by the test body, not a rider action, so it stays out
 * of [totalCalls].
 */
class RecordingNavigationManager : NavigationManager {

    val state = MutableStateFlow<NavInstruction?>(null)
    override val currentInstruction: StateFlow<NavInstruction?> = state.asStateFlow()

    var repeatManeuverCalls = 0
        private set

    val totalCalls: Int
        get() = repeatManeuverCalls

    override fun start(scope: CoroutineScope) = Unit

    override fun stop() = Unit

    override fun updateInstruction(instruction: NavInstruction?) {
        state.value = instruction
    }

    override fun repeatManeuver() { repeatManeuverCalls++ }
}

/** **Owner: the test that constructs it.** */
class MockNavigationLauncher(
    var installedApps: Set<NavigationApp> = setOf(NavigationApp.GOOGLE_MAPS),
    var succeeds: Boolean = true,
    var deviceLocked: Boolean = false
) : NavigationLauncher {

    val launchedQueries = mutableListOf<Pair<String, NavigationApp>>()

    val launchCalls: Int get() = launchedQueries.size
    val totalCalls: Int get() = launchCalls

    override fun isAppInstalled(app: NavigationApp): Boolean = installedApps.contains(app)

    override fun launchNavigation(query: String, preferredApp: NavigationApp): NavigationLaunch {
        if (!isAppInstalled(preferredApp)) return NavigationLaunch.NotStarted
        launchedQueries += query to preferredApp
        return if (succeeds) NavigationLaunch.Started(deviceLocked) else NavigationLaunch.NotStarted
    }
}

/** **Owner: the test that constructs it.** [unlock] reaches only the collectors already subscribed. */
class FakeDeviceUnlockSource : DeviceUnlockSource {

    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override val unlocks: Flow<Unit> = events

    val collectors: Int get() = events.subscriptionCount.value

    fun unlock() {
        events.tryEmit(Unit)
    }
}
