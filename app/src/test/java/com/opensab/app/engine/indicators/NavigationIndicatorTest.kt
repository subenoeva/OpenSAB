package com.opensab.app.engine.indicators

import com.opensab.app.di.NotificationModule
import com.opensab.app.di.OrchestratorModule
import com.opensab.cluster.indicators.NavigationIndicator
import com.opensab.domain.nav.ManeuverType
import com.opensab.domain.nav.NavInstruction
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.RecordingScreenProjector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The NAVIGATION bit, lit on the cluster side from the navigation source's state; the source does
 * not own it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationIndicatorTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val projector = RecordingScreenProjector()
    private val publisher = ActiveIndicatorsPublisher(projector)
    private val bus = NotificationModule.provideNavigationNotificationBus()
    private val navigation = OrchestratorModule.provideNavManager(bus)

    /**
     * NAVIGATION is on while guidance is running and clears when the route ends.
     *
     * And the bit is all that guidance writes: the base screen is owned by the engine, which
     * redraws the page the rider mounted.
     */
    @Test
    fun `the navigation bit is lit while guidance is active`() = testScope.runTest {
        navigation.start(backgroundScope)
        NavigationIndicator(navigation.currentInstruction, publisher).start(backgroundScope)
        runCurrent()

        bus.post(
            NavInstruction(
                title = "In 200 m",
                streetName = "Gran Via",
                distanceFormatted = "200 m",
                maneuver = ManeuverType.TURN_RIGHT
            )
        )
        runCurrent()
        assertEquals(listOf(ActiveIndicator.NAVIGATION.bit), projector.indicatorMasks)

        bus.post(null)
        runCurrent()
        assertEquals(listOf(ActiveIndicator.NAVIGATION.bit, 0x00), projector.indicatorMasks)

        assertEquals("guidance wrote a base screen frame", emptyList<Any>(), projector.frames)

        navigation.stop()
    }
}
