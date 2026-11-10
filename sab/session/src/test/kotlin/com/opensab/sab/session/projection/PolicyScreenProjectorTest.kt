package com.opensab.sab.session.projection


import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.VehicleCapabilitiesHolder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyScreenProjectorTest {

    private val delegate = RecordingScreenProjector()
    private val holder = VehicleCapabilitiesHolder()
    private val projector = PolicyScreenProjector(
        delegate = delegate,
        policy = VehicleFramePolicy(AsciiFoldingTextSanitizer()),
        holder = holder
    )

    private fun connect(cluster: ClusterDisplay, seriesCode: String = "0583") {
        holder.adopt(
            KnownVehicle(
                macAddress = "02:00:00:12:34:56",
                displayName = "test",
                vehicleInfo = VehicleInformation(
                    vehicleType = 0x01,
                    displayCapability = byteArrayOf(0x01, 0x02, 0x03),
                    switchType = SwitchType.TYPE_B,
                    categoryId = 0x01,
                    seriesCode = seriesCode,
                    destinationId = DestinationId.EU
                ),
                clusterDisplay = cluster
            )
        )
    }

    @Test
    fun `degrades the frame before the delegate ever sees it`() = runBlocking {
        connect(ClusterDisplay.SEGMENT)

        projector.updateBaseScreen(TestFrames.music(1, "Music", "Café", "Artist", playing = true))

        val written = delegate.frames.single() as ProjectionFrame.LiveScreen
        assertEquals("Cafe", written.contentTextLine1)
        assertEquals(ColorId.PURE_BLACK, written.headerBgColorId)
    }

    @Test
    fun `passes a colour cluster frame through untouched`() = runBlocking {
        connect(ClusterDisplay.COLOR)
        val frame = TestFrames.music(1, "Music", "Café", "Artist", playing = true)

        projector.updateBaseScreen(frame)

        assertEquals(frame, delegate.frames.single())
    }

    @Test
    fun `degrades nothing before a motorcycle has been adopted`() = runBlocking {
        val frame = TestFrames.music(1, "Music", "Café", "Artist", playing = true)

        projector.updateBaseScreen(frame)

        assertEquals(frame, delegate.frames.single())
    }

    @Test
    fun `sanitises the message of a popup it still forwards`() = runBlocking {
        connect(ClusterDisplay.SEGMENT)

        projector.postPopUp(QueuedPopUp(id = "p1", message = "Llamada de José", icon = IconId.INCOMING_CALL))

        // The decorator only cleans the text. Whether a segment cluster gets a modal at all is
        // PopupManager's call, because that is where the modal's lifetime lives.
        assertEquals("Llamada de Jose", delegate.popUps.single().message)
    }

    @Test
    fun `forwards everything else to the delegate unchanged`() = runBlocking {
        connect(ClusterDisplay.SEGMENT)

        projector.updateActiveIndicators(0x05)
        projector.dismissActivePopUp()

        assertEquals(listOf(0x05), delegate.indicatorMasks)
        assertEquals(1, delegate.dismissCount)
    }

    @Test
    fun `a color cluster projects frame without throwing and keeps original text and colors`() = runBlocking {
        connect(ClusterDisplay.COLOR, seriesCode = "ZZZZ")

        val frame = TestFrames.music(1, "Music", "Canción de Prueba", "Artista", playing = true)
        projector.updateBaseScreen(frame)

        val written = delegate.frames.single() as ProjectionFrame.LiveScreen
        assertEquals("Canción de Prueba", written.contentTextLine1)
        assertEquals("Artista", written.contentTextLine2)
        assertEquals(frame.headerBgColorId, written.headerBgColorId)
    }

    @Test
    fun `a color cluster keeps popup message diacritics intact without throwing`() = runBlocking {
        connect(ClusterDisplay.COLOR, seriesCode = "9999")
        projector.postPopUp(QueuedPopUp(id = "p-test", message = "Presión de Aceite", icon = IconId.DFU_WARNING))
        assertEquals("Presión de Aceite", delegate.popUps.single().message)
    }
}

