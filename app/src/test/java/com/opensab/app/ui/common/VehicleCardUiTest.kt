package com.opensab.app.ui.common

import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.KnownVehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleCardUiTest {

    private val VEHICLE = KnownVehicle(
        macAddress = "02:00:00:12:34:56",
        displayName = "CB650R",
        vehicleInfo = VehicleInformation(
            vehicleType = 0x01,
            displayCapability = byteArrayOf(0x01, 0x02, 0x03),
            switchType = SwitchType.TYPE_B,
            categoryId = 0x01,
            seriesCode = "0536",
            destinationId = DestinationId.EU
        )
    )

    private fun KnownVehicle.withSeries(seriesCode: String) =
        copy(vehicleInfo = vehicleInfo.copy(seriesCode = seriesCode))

    @Test
    fun aColorClusterBadgesAsColor() =
        assertEquals(ClusterBadge.COLOR, VEHICLE.toCardUi().clusterBadge)

    @Test
    fun aSegmentClusterBadgesAsSegment() =
        assertEquals(
            ClusterBadge.SEGMENT,
            VEHICLE.copy(clusterDisplay = ClusterDisplay.SEGMENT).toCardUi().clusterBadge
        )

    @Test
    fun theSeriesCodeIsShownWhenReported() =
        assertEquals("0536", VEHICLE.toCardUi().seriesCode)

    @Test
    fun aBlankSeriesCodeIsNull() =
        assertNull(VEHICLE.withSeries("").toCardUi().seriesCode)
}
