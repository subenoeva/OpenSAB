package com.opensab.domain.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The MUSIC and MUTE bits are lit on the cluster side from `currentState`, and tested in the
 * cluster module's indicators tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun startAndStopDriveTheAdapterLifecycle() = testScope.runTest {
        val adapter = LifecycleSpyAdapter()
        val musicManager: MusicManager = DefaultMusicManager(adapter)

        musicManager.start(backgroundScope)
        assertEquals(1, adapter.startCount)
        assertEquals(0, adapter.stopCount)

        musicManager.stop()
        assertEquals(1, adapter.startCount)
        assertEquals(1, adapter.stopCount)
    }

    @Test
    fun resetDelegatesToMediaAdapterReset() = testScope.runTest {
        val mediaAdapter = MockMediaControllerAdapter()
        val musicManager: MusicManager = DefaultMusicManager(mediaAdapter)

        musicManager.reset()

        assertEquals(1, mediaAdapter.resetCount)
    }

    /** Counts the system-adapter lifecycle calls the manager is expected to forward. */
    private class LifecycleSpyAdapter : MediaControllerAdapter by MockMediaControllerAdapter() {

        var startCount = 0
            private set
        var stopCount = 0
            private set

        override fun start(scope: CoroutineScope) {
            startCount++
        }

        override fun stop() {
            stopCount++
        }
    }
}
