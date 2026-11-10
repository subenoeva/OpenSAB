package com.opensab.app.source.music

import com.opensab.domain.music.MediaAppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory doubles for the two media seams :app owns. They stay in src/test rather than
 * testFixtures: both callers live in this module, and on an Android module testFixtures costs a
 * variant-aware source set that nothing outside :app would read.
 *
 * [connectsOnLaunch] mirrors the real launcher, which moves to CONNECTING synchronously inside
 * `launchAndPlay` so the busy guard holds for the very next call.
 */
class FakeMediaAppLauncher(private val connectsOnLaunch: Boolean = false) : MediaAppLauncher {
    private val _launcherState = MutableStateFlow(LauncherState.IDLE)
    override val launcherState: StateFlow<LauncherState> = _launcherState.asStateFlow()

    var lastLaunchedPackage: String? = null
    var lastServiceClassName: String? = null
    var launchCount: Int = 0
    var resetCount: Int = 0
    var lastCallback: ((Boolean) -> Unit)? = null

    fun emitState(state: LauncherState) {
        _launcherState.value = state
    }

    override fun launchAndPlay(
        packageName: String,
        serviceClassName: String?,
        onComplete: ((Boolean) -> Unit)?
    ) {
        lastLaunchedPackage = packageName
        lastServiceClassName = serviceClassName
        lastCallback = onComplete
        launchCount++
        if (connectsOnLaunch) _launcherState.value = LauncherState.CONNECTING
    }

    override fun reset() {
        resetCount++
        _launcherState.value = LauncherState.IDLE
    }
}

class FakeMediaAppScanner(
    private val apps: List<MediaAppInfo> = emptyList()
) : MediaAppScanner {
    override fun getInstalledMediaApps(): List<MediaAppInfo> = apps
}
