package com.opensab.domain.nav

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuidanceAwaiterTest {

    private val launcher = MockNavigationLauncher()
    private val unlocks = FakeDeviceUnlockSource()
    private val instructions = MutableStateFlow<NavInstruction?>(null)
    private val awaiter = GuidanceAwaiter(launcher, unlocks, instructions)

    private fun instruction(title: String) = NavInstruction(
        title = title,
        streetName = title,
        distanceFormatted = "",
        maneuver = ManeuverType.DEPART
    )

    @Test
    fun `returns the first instruction posted after the launch`() = runTest {
        val guidance = async { awaiter.await("Santiago", NavRelaunchPolicy(baseline = null)) }
        runCurrent()

        val first = instruction("Head south")
        instructions.value = first
        runCurrent()

        assertTrue(guidance.isCompleted)
        assertSame(first, guidance.await())
    }

    @Test
    fun `the instruction held at launch does not end the wait`() = runTest {
        val stale = instruction("Old route")
        instructions.value = stale
        val guidance = async { awaiter.await("Santiago", NavRelaunchPolicy(baseline = stale)) }
        runCurrent()

        assertFalse(guidance.isCompleted)
        guidance.cancelAndJoin()
    }

    @Test
    fun `an unlock before guidance re-sends the same query`() = runTest {
        val guidance = async { awaiter.await("Santiago", NavRelaunchPolicy(baseline = null)) }
        runCurrent()

        unlocks.unlock()
        runCurrent()

        assertEquals(listOf("Santiago" to NavigationApp.GOOGLE_MAPS), launcher.launchedQueries)
        guidance.cancelAndJoin()
    }

    @Test
    fun `a third unlock re-sends nothing`() = runTest {
        val guidance = async { awaiter.await("Santiago", NavRelaunchPolicy(baseline = null)) }
        runCurrent()

        repeat(3) {
            unlocks.unlock()
            runCurrent()
        }

        assertEquals(NavRelaunchPolicy.MAX_RELAUNCHES, launcher.launchCalls)
        guidance.cancelAndJoin()
    }

    @Test
    fun `once the wait is cancelled an unlock re-sends nothing`() = runTest {
        val guidance = async { awaiter.await("Santiago", NavRelaunchPolicy(baseline = null)) }
        runCurrent()

        guidance.cancelAndJoin()
        unlocks.unlock()
        runCurrent()

        assertEquals(0, launcher.launchCalls)
        assertEquals(0, unlocks.collectors)
    }

    @Test
    fun `an unlock after guidance started re-sends nothing`() = runTest {
        val guidance = async { awaiter.await("Santiago", NavRelaunchPolicy(baseline = null)) }
        runCurrent()
        instructions.value = instruction("Head south")
        runCurrent()
        guidance.await()

        unlocks.unlock()
        runCurrent()

        assertEquals(0, launcher.launchCalls)
        assertEquals(0, unlocks.collectors)
    }
}
