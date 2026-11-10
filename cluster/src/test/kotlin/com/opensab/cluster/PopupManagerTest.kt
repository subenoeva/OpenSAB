package com.opensab.cluster

import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorPopUp
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.MirrorState
import com.opensab.cluster.mirror.ProjectionMirror
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import com.opensab.sab.session.projection.PopUpPriority
import com.opensab.sab.session.projection.QueuedPopUp
import com.opensab.sab.session.projection.RecordingScreenProjector
import com.opensab.sab.session.projection.ScreenProjector
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Modal arbitration.
 *
 * Popups are arbitrated by priority, a higher priority modal preempts the one on screen and the
 * suspended one stays queued. SELECT on an incoming call popup dismisses the modal and restores the
 * previous view, which is retained because the page underneath declares ResumeBehavior.RETAIN.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PopupManagerTest {

    /**
     * Test-only stand-in for the defaults [PopupManager] deliberately no longer carries.
     *
     * Production has to name every collaborator so a module that forgets one fails to compile;
     * the convenience belongs here, where an omission cannot reach a motorcycle.
     */
    private fun popupManager(
        projector: ScreenProjector,
        onRestoreBase: suspend () -> Unit = {},
        capabilities: VehicleCapabilitiesHolder = VehicleCapabilitiesHolder(),
        activeIndicators: ActiveIndicatorsPublisher = ActiveIndicatorsPublisher(projector),
        projectionMirror: ProjectionMirror = ProjectionMirror()
    ) = PopupManager(
        screenProjector = projector,
        onRestoreBase = onRestoreBase,
        capabilities = capabilities,
        activeIndicators = activeIndicators,
        projectionMirror = projectionMirror
    )

    /**
     * A projector whose dismiss actually suspends, the way the real one does the instant it writes
     * to the ControlPoint.
     *
     * [RecordingScreenProjector] never suspends, so a coroutine that is already cancelled runs
     * straight through it and the cancellation is never observed, which would hide the expiry
     * watchdog cancelling itself.
     */
    private class SuspendingDismissProjector(
        private val delegate: RecordingScreenProjector = RecordingScreenProjector()
    ) : ScreenProjector by delegate {

        var dismissCompleted = false
            private set

        override suspend fun dismissActivePopUp(): Result<Unit> {
            yield()
            dismissCompleted = true
            return delegate.dismissActivePopUp()
        }
    }

    /** Mounts a page so the mirror has something for the modal to sit on top of. */
    private fun ProjectionMirror.withPageMounted(): ProjectionMirror = apply {
        publishPage(
            MirrorPage(
                pageName = "MusicPage",
                title = "Music",
                lines = listOf("Bohemian Rhapsody"),
                accent = ColorId.ORANGE,
                actions = emptyList()
            ),
            trail = listOf("RootMenu", "MusicPage")
        )
    }

    private val ProjectionMirror.mirroredPopUp: MirrorPopUp?
        get() = (state.value as MirrorState.Projecting).popUp


    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun popUp(id: String, priority: PopUpPriority) = QueuedPopUp(
        id = id,
        message = id,
        icon = IconId.INCOMING_MESSAGE,
        priority = priority
    )

    @Test
    fun presentsAHigherPriorityPopupOverTheActiveOne() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val manager = popupManager(projector)
        val alert = popUp("nav-alert", PopUpPriority.NAVIGATION_ALERT)
        val call = popUp("incoming-call", PopUpPriority.INCOMING_CALL)

        manager.post(alert)
        manager.post(call)

        assertEquals(listOf(alert, call), projector.popUps)
        assertEquals(call, manager.activePopUp)
    }

    @Test
    fun keepsTheActivePopupWhenALowerPriorityOneArrives() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val manager = popupManager(projector)
        val call = popUp("incoming-call", PopUpPriority.INCOMING_CALL)
        val alert = popUp("nav-alert", PopUpPriority.NAVIGATION_ALERT)

        manager.post(call)
        manager.post(alert)

        assertEquals(listOf(call), projector.popUps)
        assertEquals(call, manager.activePopUp)
    }

    @Test
    fun presentsTheQueuedPopupWhenTheActiveOneIsDismissed() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val manager = popupManager(projector)
        val call = popUp("incoming-call", PopUpPriority.INCOMING_CALL)
        val alert = popUp("nav-alert", PopUpPriority.NAVIGATION_ALERT)
        manager.post(call)
        manager.post(alert)

        manager.dismissActive()

        assertEquals(alert, manager.activePopUp)
        assertEquals(listOf(call, alert), projector.popUps)
    }

    @Test
    fun restoresTheRetainedBasePageWhenTheQueueDrains() = testScope.runTest {
        val projector = RecordingScreenProjector()
        var restoreCount = 0
        val manager = popupManager(projector, onRestoreBase = { restoreCount++ })
        manager.post(popUp("incoming-call", PopUpPriority.INCOMING_CALL))

        manager.dismissActive()

        assertNull(manager.activePopUp)
        assertEquals(1, projector.dismissCount)
        assertEquals("The retained page underneath must be projected again", 1, restoreCount)
    }

    @Test
    fun consumesSelectAndBackWhileAModalIsOnScreen() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val manager = popupManager(projector)
        manager.post(popUp("incoming-call", PopUpPriority.INCOMING_CALL))

        val consumed = manager.onInput(FunctionId.SELECT)

        assertTrue("A modal on screen swallows the handlebar press", consumed)
        assertNull(manager.activePopUp)
    }

    @Test
    fun ignoresHandlebarInputWhenNoModalIsOnScreen() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val manager = popupManager(projector)

        assertEquals(false, manager.onInput(FunctionId.SELECT))
        assertEquals(0, projector.dismissCount)
    }

    /**
     * A modal lives for its own duration and, once it expires,
     * the layer underneath comes back. The timer belongs to the modal layer, which is the only
     * thing that knows the queue and the retained page; DefaultScreenProjector must not schedule a
     * second one behind its back.
     */
    @Test
    fun autoDismissesTheActivePopUpWhenItsDurationExpires() = testScope.runTest {
        val projector = RecordingScreenProjector()
        var restoreCount = 0
        val manager = popupManager(projector, onRestoreBase = { restoreCount++ })
        manager.start(backgroundScope)

        val call = QueuedPopUp(
            id = "incoming-call",
            message = "Alice",
            icon = IconId.INCOMING_CALL,
            priority = PopUpPriority.INCOMING_CALL,
            duration = 5.seconds
        )
        manager.post(call)
        assertEquals(call, manager.activePopUp)

        advanceTimeBy(5_001.milliseconds)

        assertNull("The expired modal must be gone", manager.activePopUp)
        assertEquals("The dismiss frame must be projected once", 1, projector.dismissCount)
        assertEquals("The page underneath must come back", 1, restoreCount)
    }

    private fun segmentClusterVehicle() = com.opensab.sab.session.vehicle.KnownVehicle(
        macAddress = "02:00:00:12:34:56",
        displayName = "test",
        vehicleInfo = com.opensab.sab.protocol.vehicle.VehicleInformation(
            vehicleType = 0x01,
            displayCapability = byteArrayOf(0x01, 0x02, 0x03),
            switchType = com.opensab.sab.protocol.vehicle.SwitchType.TYPE_B,
            categoryId = 0x01,
            seriesCode = "052B",
            destinationId = com.opensab.sab.protocol.vehicle.DestinationId.EU
        ),
        clusterDisplay = com.opensab.sab.session.vehicle.ClusterDisplay.SEGMENT
    )

    @Test
    fun `turns a modal into a status bar bit when the cluster has no modal layer`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val publisher = ActiveIndicatorsPublisher(projector)
        val holder = VehicleCapabilitiesHolder()
        holder.adopt(segmentClusterVehicle())
        val manager = popupManager(projector, capabilities = holder, activeIndicators = publisher)
        manager.start(backgroundScope)

        manager.post(
            QueuedPopUp(id = "call", message = "Llamada", icon = IconId.INCOMING_CALL, priority = PopUpPriority.INCOMING_CALL)
        )

        assertTrue("No modal may be written to a segment cluster", projector.popUps.isEmpty())
        assertEquals(com.opensab.sab.protocol.projection.ActiveIndicator.PHONE.bit, publisher.mask)
    }

    @Test
    fun `clears the degraded bit once the popup would have expired`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val publisher = ActiveIndicatorsPublisher(projector)
        val holder = VehicleCapabilitiesHolder()
        holder.adopt(segmentClusterVehicle())
        val manager = popupManager(projector, capabilities = holder, activeIndicators = publisher)
        manager.start(backgroundScope)

        manager.post(
            QueuedPopUp(
                id = "nav",
                message = "Gire a la derecha",
                icon = IconId.NAVIGATION,
                priority = PopUpPriority.NAVIGATION_ALERT,
                duration = 5.seconds
            )
        )
        assertEquals(com.opensab.sab.protocol.projection.ActiveIndicator.NAVIGATION.bit, publisher.mask)

        advanceTimeBy(5_001.milliseconds)

        // A bit nobody switches off stays lit for the rest of the ride.
        assertEquals(0, publisher.mask)
    }

    @Test
    fun `does not touch the indicators of other adapters when it degrades`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val publisher = ActiveIndicatorsPublisher(projector)
        publisher.set(com.opensab.sab.protocol.projection.ActiveIndicator.MUSIC, enabled = true)
        val holder = VehicleCapabilitiesHolder()
        holder.adopt(segmentClusterVehicle())
        val manager = popupManager(projector, capabilities = holder, activeIndicators = publisher)
        manager.start(backgroundScope)

        manager.post(
            QueuedPopUp(id = "call", message = "Llamada", icon = IconId.INCOMING_CALL, priority = PopUpPriority.INCOMING_CALL)
        )

        // ACTIVE_INDICATORS carries the whole mask, so writing one bit directly would blank MUSIC.
        assertEquals(
            com.opensab.sab.protocol.projection.ActiveIndicator.MUSIC.bit or com.opensab.sab.protocol.projection.ActiveIndicator.PHONE.bit,
            publisher.mask
        )
    }

    @Test
    fun `still projects a real modal on a colour cluster`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val publisher = ActiveIndicatorsPublisher(projector)
        val holder = VehicleCapabilitiesHolder()   // Permissive by default
        val manager = popupManager(projector, capabilities = holder, activeIndicators = publisher)
        manager.start(backgroundScope)

        manager.post(QueuedPopUp(id = "call", message = "Llamada", icon = IconId.INCOMING_CALL))

        assertEquals(1, projector.popUps.size)
        assertEquals(0, publisher.mask)
    }

    // -------------------------------------------------------------------------------------------
    // Mirror
    // -------------------------------------------------------------------------------------------

    /**
     * The mirrored modal declares what `onInput` does, not what the cluster icons suggest.
     *
     * `DefaultScreenProjector.postPopUp` paints POPUP_HANGUP on the left and POPUP_ACCEPT on the
     * right, but [PopupManager.onInput] treats SELECT and BACK identically — both call
     * `dismissActive` and neither reaches the telecom adapter. Both zones therefore carry
     * [MirrorActionToken.DISMISS]: the mirror cannot promise what the engine does not do.
     */
    @Test
    fun `present mirrors the modal with two dismiss zones`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val mirror = ProjectionMirror().withPageMounted()
        val manager = popupManager(projector, projectionMirror = mirror)
        manager.start(backgroundScope)

        manager.post(
            QueuedPopUp(
                id = "incoming-call",
                message = "Alice",
                icon = IconId.INCOMING_CALL,
                priority = PopUpPriority.INCOMING_CALL
            )
        )

        assertEquals(
            MirrorPopUp(
                id = "incoming-call",
                message = "Alice",
                priority = PopUpPriority.INCOMING_CALL,
                actions = listOf(
                    com.opensab.cluster.mirror.MirrorAction(
                        token = MirrorActionToken.DISMISS,
                        functionId = FunctionId.BACK,
                        slot = MirrorSlot.LEFT
                    ),
                    com.opensab.cluster.mirror.MirrorAction(
                        token = MirrorActionToken.DISMISS,
                        functionId = FunctionId.SELECT,
                        slot = MirrorSlot.RIGHT
                    )
                )
            ),
            mirror.mirroredPopUp
        )
    }

    @Test
    fun `dismissActive drops the mirrored modal when the queue is empty`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val mirror = ProjectionMirror().withPageMounted()
        val manager = popupManager(projector, projectionMirror = mirror)
        manager.start(backgroundScope)
        manager.post(QueuedPopUp(id = "call", message = "Alice", icon = IconId.INCOMING_CALL))

        manager.dismissActive()

        assertNull(mirror.mirroredPopUp)
    }

    @Test
    fun `dismissActive mirrors the next queued modal`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val mirror = ProjectionMirror().withPageMounted()
        val manager = popupManager(projector, projectionMirror = mirror)
        manager.start(backgroundScope)
        manager.post(popUp("incoming-call", PopUpPriority.INCOMING_CALL))
        manager.post(popUp("nav-alert", PopUpPriority.NAVIGATION_ALERT))

        manager.dismissActive()

        assertEquals("nav-alert", mirror.mirroredPopUp?.id)
    }

    /**
     * With no modal layer the popup becomes a status-bar bit, and the mirror
     * follows that degradation to the letter. A modal drawn in the app here would show the rider
     * something that is not in front of them.
     */
    @Test
    fun `a degraded popup lights the indicator and mirrors no modal`() = testScope.runTest {
        val projector = RecordingScreenProjector()
        val publisher = ActiveIndicatorsPublisher(projector)
        val holder = VehicleCapabilitiesHolder()
        holder.adopt(segmentClusterVehicle())
        val mirror = ProjectionMirror().withPageMounted()
        val manager = popupManager(
            projector,
            capabilities = holder,
            activeIndicators = publisher,
            projectionMirror = mirror
        )
        manager.start(backgroundScope)

        manager.post(QueuedPopUp(id = "call", message = "Alice", icon = IconId.INCOMING_CALL, priority = PopUpPriority.INCOMING_CALL))

        assertEquals(com.opensab.sab.protocol.projection.ActiveIndicator.PHONE.bit, publisher.mask)
        assertNull("The rider has no modal in front of them", mirror.mirroredPopUp)
    }

    /**
     * The expiry watchdog must not cancel the dismissal it is itself running.
     *
     * [PopupManager.dismissActive] drops the watchdog because the rider can dismiss the modal
     * first. When the dismissal *is* the watchdog firing, cancelling it there kills the coroutine
     * doing the work: `Job.cancel()` on your own job does not stop the current line, it makes the
     * next suspension point throw, so neither the dismiss frame nor the page underneath ever
     * reaches the cluster.
     */
    @Test
    fun `the expiry watchdog does not cancel the dismissal it is running`() = testScope.runTest {
        val projector = SuspendingDismissProjector()
        var restoreCount = 0
        val manager = popupManager(projector, onRestoreBase = { restoreCount++ })
        manager.start(backgroundScope)

        manager.post(
            QueuedPopUp(id = "nav-alert", message = "Turn right in 200 m", icon = IconId.NAVIGATION, duration = 5.seconds)
        )

        advanceTimeBy(5_001.milliseconds)
        runCurrent()

        assertTrue(
            "The dismiss write has to run past its first suspension point",
            projector.dismissCompleted
        )
        assertEquals("The page underneath must come back", 1, restoreCount)
    }
}
