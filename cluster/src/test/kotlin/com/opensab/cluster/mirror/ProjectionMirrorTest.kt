package com.opensab.cluster.mirror

import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.protocol.projection.CommandId
import com.opensab.sab.protocol.projection.Receipt
import com.opensab.sab.protocol.projection.ReceiptStatus
import com.opensab.sab.session.projection.PopUpPriority
import com.opensab.sab.session.projection.ProjectionRejectedException
import com.opensab.sab.session.projection.ProjectionTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Sole owner of the mirrored state.
 *
 * The governing rule — the mirror cannot show what the cluster does not show — lands here as a
 * transition rule: only [ProjectionMirror.publishPage]
 * mounts a page. Everything else describes a page already on screen, so on any other state it is
 * dropped rather than promoted into a `Projecting` the cluster never reached.
 */
class ProjectionMirrorTest {

    private fun page(name: String = "MusicPage") = MirrorPage(
        pageName = name,
        title = "Music",
        lines = listOf("Bohemian Rhapsody", "Queen"),
        accent = ColorId.ORANGE,
        actions = listOf(
            MirrorAction(MirrorActionToken.PAUSE, FunctionId.BACK, MirrorSlot.PRIMARY)
        )
    )

    private fun popUp() = MirrorPopUp(
        id = "incoming-call",
        message = "Alice",
        priority = PopUpPriority.INCOMING_CALL,
        actions = listOf(
            MirrorAction(MirrorActionToken.DISMISS, FunctionId.BACK, MirrorSlot.LEFT),
            MirrorAction(MirrorActionToken.DISMISS, FunctionId.SELECT, MirrorSlot.RIGHT)
        )
    )

    // -------------------------------------------------------------------------------------------
    // States
    // -------------------------------------------------------------------------------------------

    @Test
    fun `starts idle`() {
        assertEquals(MirrorState.Idle, ProjectionMirror().state.value)
    }

    @Test
    fun `publishClusterOwnsScreen hands the screen back to the tachometer`() {
        val mirror = ProjectionMirror()

        mirror.publishClusterOwnsScreen()

        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = false), mirror.state.value)
    }

    @Test
    fun `markHandoverRequested lights the pending flag on ClusterOwnsScreen`() {
        val mirror = ProjectionMirror()
        mirror.publishClusterOwnsScreen()

        mirror.markHandoverRequested()

        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = true), mirror.state.value)
    }

    /**
     * There is nothing to ask for while the mirror is idle or already projecting, and a pending
     * flag on either would arm a button that stands for no outstanding request.
     */
    @Test
    fun `markHandoverRequested is dropped anywhere but on ClusterOwnsScreen`() {
        val idle = ProjectionMirror()
        idle.markHandoverRequested()
        assertEquals(MirrorState.Idle, idle.state.value)

        val projecting = ProjectionMirror()
        projecting.publishPage(page(), trail = listOf("RootMenu"))
        projecting.markHandoverRequested()
        assertTrue(projecting.state.value is MirrorState.Projecting)
    }

    /**
     * `publishClusterOwnsScreen` says where the screen is, not whether the request was answered. A
     * re-delivery of `HU_MODE` — the one every `BaseStateManager.start` gets on re-subscribing to
     * `currentMode` — is not a verdict, so the button the rider is waiting on stays disabled.
     */
    @Test
    fun `publishClusterOwnsScreen keeps an outstanding request in flight`() {
        val mirror = ProjectionMirror()
        mirror.publishClusterOwnsScreen()
        mirror.markHandoverRequested()

        mirror.publishClusterOwnsScreen()

        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = true), mirror.state.value)
    }

    /**
     * Coming from anywhere else there is no request in flight to preserve: arriving from
     * `Projecting` means the cluster took the screen back, which nobody asked for.
     */
    @Test
    fun `publishClusterOwnsScreen starts the flag down from Idle and from Projecting`() {
        val fromIdle = ProjectionMirror()
        fromIdle.publishClusterOwnsScreen()
        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = false), fromIdle.state.value)

        val fromProjecting = ProjectionMirror()
        fromProjecting.publishPage(page(), trail = listOf("RootMenu"))
        fromProjecting.publishClusterOwnsScreen()
        assertEquals(
            MirrorState.ClusterOwnsScreen(handoverPending = false),
            fromProjecting.state.value
        )
    }

    /** This one **is** the verdict, so it always hands the handover button back to the rider. */
    @Test
    fun `publishHandoverDenied clears a pending handover`() {
        val mirror = ProjectionMirror()
        mirror.publishClusterOwnsScreen()
        mirror.markHandoverRequested()

        mirror.publishHandoverDenied()

        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = false), mirror.state.value)
    }

    /**
     * The watchdog dictating its verdict is as good a reason to fix the state as the mode itself:
     * a cluster that refuses the screen is a cluster that owns it.
     */
    @Test
    fun `publishHandoverDenied puts the screen on the tachometer from any state`() {
        val mirror = ProjectionMirror()

        mirror.publishHandoverDenied()

        assertEquals(MirrorState.ClusterOwnsScreen(handoverPending = false), mirror.state.value)
    }

    /**
     * The wait for the 0x09 ran out. The button comes back, and the state remembers that the
     * cluster kept its screen so the dashboard can suggest what has released it before.
     */
    @Test
    fun `publishGrantOverdue hands the button back and remembers the cluster kept its screen`() {
        val mirror = ProjectionMirror()
        mirror.publishClusterOwnsScreen()
        mirror.markHandoverRequested()

        mirror.publishGrantOverdue()

        assertEquals(
            MirrorState.ClusterOwnsScreen(handoverPending = false, grantOverdue = true),
            mirror.state.value
        )
    }

    /** A re-delivery of HU_MODE says where the screen is; it does not take the suggestion back. */
    @Test
    fun `publishClusterOwnsScreen keeps an overdue grant`() {
        val mirror = ProjectionMirror()
        mirror.publishClusterOwnsScreen()
        mirror.publishGrantOverdue()

        mirror.publishClusterOwnsScreen()

        assertEquals(MirrorState.ClusterOwnsScreen(grantOverdue = true), mirror.state.value)
    }

    @Test
    fun `a new request starts with no overdue grant`() {
        val mirror = ProjectionMirror()
        mirror.publishClusterOwnsScreen()
        mirror.publishGrantOverdue()

        mirror.markHandoverRequested()

        assertEquals(
            MirrorState.ClusterOwnsScreen(handoverPending = true, grantOverdue = false),
            mirror.state.value
        )
    }

    /** Only where the cluster owns the screen: it neither mounts a tachometer nor unmounts a page. */
    @Test
    fun `publishGrantOverdue is dropped anywhere but on ClusterOwnsScreen`() {
        val idle = ProjectionMirror()
        idle.publishGrantOverdue()
        assertEquals(MirrorState.Idle, idle.state.value)

        val projecting = ProjectionMirror().also { it.publishPage(page(), trail = emptyList()) }
        val before = projecting.state.value
        projecting.publishGrantOverdue()
        assertEquals(before, projecting.state.value)
    }

    @Test
    fun `a page mounted after an overdue grant leaves no trace of it`() {
        val mirror = ProjectionMirror()
        mirror.publishClusterOwnsScreen()
        mirror.publishGrantOverdue()

        mirror.publishPage(page(), trail = emptyList())
        mirror.publishClusterOwnsScreen()

        assertEquals(MirrorState.ClusterOwnsScreen(), mirror.state.value)
    }

    @Test
    fun `publishPage mounts the page in flight with its trail`() {
        val mirror = ProjectionMirror()

        mirror.publishPage(page(), trail = listOf("RootMenu", "MusicPage"))

        assertEquals(
            MirrorState.Projecting(
                page = page(),
                trail = listOf("RootMenu", "MusicPage"),
                delivery = MirrorDelivery.InFlight
            ),
            mirror.state.value
        )
    }

    @Test
    fun `publishPage carries the indicators over and resets the delivery`() {
        val mirror = ProjectionMirror()
        mirror.publishPage(page(), trail = listOf("RootMenu"))
        mirror.publishIndicators(setOf(ActiveIndicator.MUSIC))
        mirror.publishDelivery(MirrorDelivery.Confirmed)

        mirror.publishPage(page("VolumePage"), trail = listOf("RootMenu", "VolumePage"))

        val state = mirror.state.value as MirrorState.Projecting
        assertEquals("VolumePage", state.page.pageName)
        assertEquals(setOf(ActiveIndicator.MUSIC), state.indicators)
        assertEquals(
            "A fresh frame is in flight until the cluster answers",
            MirrorDelivery.InFlight,
            state.delivery
        )
    }

    @Test
    fun `clear returns to idle`() {
        val mirror = ProjectionMirror()
        mirror.publishPage(page(), trail = listOf("RootMenu"))

        mirror.clear()

        assertEquals(MirrorState.Idle, mirror.state.value)
    }

    // -------------------------------------------------------------------------------------------
    // Only publishPage mounts a page
    // -------------------------------------------------------------------------------------------

    @Test
    fun `delivery, popup and indicators are dropped while no page is mounted`() {
        for (mirror in listOf(ProjectionMirror(), ProjectionMirror().also { it.publishClusterOwnsScreen() })) {
            val before = mirror.state.value

            mirror.publishDelivery(MirrorDelivery.Confirmed)
            mirror.publishPopUp(popUp())
            mirror.publishIndicators(setOf(ActiveIndicator.PHONE))

            assertEquals(
                "Only publishPage mounts a page; describing one that is not there would show " +
                    "what the cluster does not",
                before,
                mirror.state.value
            )
        }
    }

    @Test
    fun `publishDelivery marks the mounted page`() {
        val mirror = ProjectionMirror()
        mirror.publishPage(page(), trail = listOf("RootMenu"))

        mirror.publishDelivery(MirrorDelivery.Rejected(status = 2, errorPosition = 7))

        assertEquals(
            MirrorDelivery.Rejected(status = 2, errorPosition = 7),
            (mirror.state.value as MirrorState.Projecting).delivery
        )
        assertEquals(
            "A rejected frame leaves the mirror painted and stale: the cluster still shows the " +
                "previous page",
            page(),
            (mirror.state.value as MirrorState.Projecting).page
        )
    }

    @Test
    fun `publishPopUp raises and drops the modal layer`() {
        val mirror = ProjectionMirror()
        mirror.publishPage(page(), trail = listOf("RootMenu"))

        mirror.publishPopUp(popUp())
        assertEquals(popUp(), (mirror.state.value as MirrorState.Projecting).popUp)

        mirror.publishPopUp(null)
        assertEquals(null, (mirror.state.value as MirrorState.Projecting).popUp)
    }

    @Test
    fun `publishIndicators replaces the whole set`() {
        val mirror = ProjectionMirror()
        mirror.publishPage(page(), trail = listOf("RootMenu"))

        mirror.publishIndicators(setOf(ActiveIndicator.MUSIC, ActiveIndicator.MUTE))
        mirror.publishIndicators(setOf(ActiveIndicator.NAVIGATION))

        assertEquals(
            setOf(ActiveIndicator.NAVIGATION),
            (mirror.state.value as MirrorState.Projecting).indicators
        )
    }

    // -------------------------------------------------------------------------------------------
    // Delivery mapping
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a receipt confirms the frame`() {
        val receipt = Receipt(CommandId.MYSTERY_BOX, 1, ReceiptStatus.SUCCESS)

        assertEquals(MirrorDelivery.Confirmed, mirrorDeliveryOf(Result.success(receipt)))
    }

    @Test
    fun `a receipt on an assumed grant does not claim the cluster shows the frame`() {
        assertEquals(
            MirrorDelivery.HandoverUnconfirmed,
            mirrorDeliveryOf(Result.success(Unit), handoverConfirmed = false)
        )
    }

    @Test
    fun `a failure on an assumed grant is the same failure`() {
        assertEquals(
            MirrorDelivery.Rejected(status = 0x0C, errorPosition = 7),
            mirrorDeliveryOf(
                Result.failure<Unit>(ProjectionRejectedException(status = 0x0C, errorPosition = 7)),
                handoverConfirmed = false
            )
        )
    }

    @Test
    fun `a rejection carries the status and the error position through`() {
        val failure = Result.failure<Receipt>(
            ProjectionRejectedException(status = 3, errorPosition = 12)
        )

        assertEquals(MirrorDelivery.Rejected(status = 3, errorPosition = 12), mirrorDeliveryOf(failure))
    }

    @Test
    fun `a timeout is a timeout`() {
        val failure = Result.failure<Receipt>(ProjectionTimeoutException("no receipt in 3000 ms"))

        assertEquals(MirrorDelivery.TimedOut, mirrorDeliveryOf(failure))
    }

    /**
     * No silent default: anything else the write path can throw — a dropped link, a closed
     * transport — is a frame the cluster never acknowledged, which is what `TimedOut` means to the
     * rider. It is logged so the case is not merely absorbed.
     */
    @Test
    fun `any other failure reads as a timeout`() {
        val failure = Result.failure<Receipt>(IOException("GATT link dropped"))

        assertEquals(MirrorDelivery.TimedOut, mirrorDeliveryOf(failure))
    }
}
