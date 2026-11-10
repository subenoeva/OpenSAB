package com.opensab.app.source.nav

import com.opensab.app.di.NotificationModule
import com.opensab.app.di.OrchestratorModule
import com.opensab.domain.nav.DefaultNavigationManager
import com.opensab.domain.nav.ManeuverType
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.nav.NavigationManager
import com.opensab.domain.nav.NavigationNotificationBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The instruction flow. The NAVIGATION bit is lit on the cluster side from `currentInstruction`, and
 * tested there (`com.opensab.app.engine.indicators.NavigationIndicatorTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)


    @Test
    fun receivesInstructionsFromNotificationBusWhenStarted() = testScope.runTest {
        val bus = NavigationNotificationBus()
        val navManager: NavigationManager = DefaultNavigationManager(bus)
        navManager.start(backgroundScope)

        val instruction = NavInstruction(
            title = "A 500 m",
            streetName = "Paseo de la Castellana",
            distanceFormatted = "500 m",
            maneuver = ManeuverType.ROUNDABOUT
        )

        bus.post(instruction)

        assertEquals("Paseo de la Castellana", navManager.currentInstruction.value?.streetName)
        assertEquals(ManeuverType.ROUNDABOUT, navManager.currentInstruction.value?.maneuver)

        bus.post(null)
        assertNull(navManager.currentInstruction.value)

        navManager.stop()
    }

    /**
     * Unlike `CallNotificationBus`, `NavigationNotificationBus` replays nothing. An instruction
     * posted before the source starts must not reach a collector that only starts listening
     * afterwards. Built through `OrchestratorModule.provideNavManager`, the same factory the app's
     * graph uses.
     *
     * A plain `runTest`, not the class's `UnconfinedTestDispatcher` scope: the standard dispatcher
     * queues work the way the session thread does in production, so `post` before `start` is really
     * unobserved and not merely undispatched yet.
     */
    @Test
    fun anInstructionPostedBeforeStartIsNotReplayed() = runTest {
        val bus = NotificationModule.provideNavigationNotificationBus()
        val navManager: NavigationManager = OrchestratorModule.provideNavManager(bus)

        bus.post(
            NavInstruction(
                title = "A 500 m",
                streetName = "Paseo de la Castellana",
                distanceFormatted = "500 m",
                maneuver = ManeuverType.ROUNDABOUT
            )
        )

        navManager.start(backgroundScope)
        runCurrent()

        assertNull(
            "the navigation bus replays nothing: an instruction posted before start is gone",
            navManager.currentInstruction.value
        )

        navManager.stop()
    }
}
