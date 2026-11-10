package com.opensab.cluster

import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.domain.call.MockCallDialer
import com.opensab.domain.call.RecordingTelecomAdapter
import com.opensab.domain.contacts.MockCallMenuAccess
import com.opensab.domain.contacts.MockContactDirectory
import com.opensab.domain.message.MockMessageMenuAccess
import com.opensab.domain.message.MockMessageSource
import com.opensab.domain.message.MockSmsSender
import com.opensab.domain.music.RecordingMusicManager
import com.opensab.domain.music.RecordingVolumeSource
import com.opensab.domain.nav.MockNavigationLauncher
import com.opensab.domain.nav.RecordingNavigationManager
import com.opensab.domain.settings.RootMenuEntry
import com.opensab.domain.weather.MockWeatherSource
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


/** Minimal page used to assert stack transitions without pulling in a real feature page.
 *
 * Owner: the test that constructs it.
 */
class StubPageProvider(
    override val name: String,
    private val frame: ProjectionFrame,
    private val inputResult: (FunctionId) -> NavigationAction = { NavigationAction.None },
    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN,
    override val invalidations: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()
) : PageProvider {

    var renderCount = 0
        private set

    override fun render(requestId: Int): ProjectionFrame {
        renderCount++
        return frame
    }

    override fun onInput(functionId: FunctionId): NavigationAction = inputResult(functionId)

    override fun mirror(): MirrorPage =
        mirrorPageOf(name, render(MIRROR_REQUEST_ID), actions = emptyList())
}


/**
 * The doubles one page was wired with, behind a single aggregate counter.
 *
 * Weather and message reuse the `main` mocks, which already count the only action each page asks of
 * them: `MockWeatherSource.refreshCalls` and `MockMessageSource.yieldCalls`.
 *
 * Owner: the test that constructs it.
 */
class RecordingDoubles(
    val volume: RecordingVolumeSource? = null,
    val music: RecordingMusicManager? = null,
    val navigation: RecordingNavigationManager? = null,
    val weather: MockWeatherSource? = null,
    val message: MockMessageSource? = null,
    val telecom: RecordingTelecomAdapter? = null,
    val contacts: MockContactDirectory? = null,
    val dialer: MockCallDialer? = null,
    val access: MockCallMenuAccess? = null,
    val messageAccess: MockMessageMenuAccess? = null,
    val launcher: MockNavigationLauncher? = null,
    val smsSender: MockSmsSender? = null
) {

    val totalCalls: Int
        get() = (volume?.totalCalls ?: 0) +
            (music?.totalCalls ?: 0) +
            (navigation?.totalCalls ?: 0) +
            (weather?.refreshCalls ?: 0) +
            (message?.yieldCalls ?: 0) +
            (telecom?.totalCalls ?: 0) +
            (contacts?.totalCalls ?: 0) +
            (dialer?.totalCalls ?: 0) +
            (access?.gatesCalls ?: 0) +
            (messageAccess?.gatesCalls ?: 0) +
            (launcher?.totalCalls ?: 0) +
            (smsSender?.sendCount ?: 0)
}

/** The factory layout, every entry visible: what the carousel shows before the rider touches it. */
fun allRootMenuEntries(): StateFlow<List<RootMenuEntry>> = MutableStateFlow(RootMenuEntry.entries.toList())
