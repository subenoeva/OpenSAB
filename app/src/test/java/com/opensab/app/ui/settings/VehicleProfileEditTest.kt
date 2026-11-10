package com.opensab.app.ui.settings

import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.vehicle.BodyStyle
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.KnownVehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VehicleProfileEditTest {

    private val VEHICLE = KnownVehicle(
        macAddress = "AA:BB:CC:11:22:33",
        displayName = "Honda 0536",
        vehicleInfo = VehicleInformation(
            vehicleType = 0x01,
            displayCapability = byteArrayOf(0x01, 0x02),
            switchType = SwitchType.TYPE_B,
            categoryId = 0x01,
            seriesCode = "0536",
            destinationId = DestinationId.EU
        )
    )

    @Test
    fun theNameIsTrimmed() = assertEquals("Mi CB", draft(name = "  Mi CB  ").toProfile("0536").name)

    @Test
    fun aBlankNameFallsBackToTheDefaultName() =
        assertEquals("Honda 0536", draft(name = "   ").toProfile("0536").name)

    @Test
    fun aLongNameIsCutAtThirtyCodePoints() {
        val name = draft(name = "x".repeat(80)).toProfile("0536").name!!
        assertEquals(30, name.codePointCount(0, name.length))
    }

    @Test
    fun theCutNeverSplitsASurrogatePair() {
        val name = draft(name = "x".repeat(29) + "🏍️🏍️").toProfile("0536").name!!
        assertFalse(name.last().isHighSurrogate())
        assertEquals(30, name.codePointCount(0, name.length))
    }

    @Test
    fun cutToCodePointsLeavesAShorterStringUntouched() =
        assertEquals("Mi CB", "Mi CB".cutToCodePoints(30))

    @Test
    fun cutToCodePointsCutsWithoutSplittingASurrogatePair() {
        val cut = ("x".repeat(29) + "🏍️🏍️").cutToCodePoints(30)
        assertFalse(cut.last().isHighSurrogate())
        assertEquals(30, cut.codePointCount(0, cut.length))
    }

    @Test
    fun theDraftStartsFromWhatIsStored() =
        assertEquals(VehicleProfileDraft("Honda 0536", BodyStyle.GENERIC, ClusterDisplay.COLOR), VEHICLE.toDraft())

    private fun draft(
        name: String = "Honda 0536",
        bodyStyle: BodyStyle = BodyStyle.GENERIC,
        clusterDisplay: ClusterDisplay = ClusterDisplay.COLOR
    ) = VehicleProfileDraft(name, bodyStyle, clusterDisplay)
}
