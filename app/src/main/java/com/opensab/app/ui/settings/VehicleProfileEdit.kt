package com.opensab.app.ui.settings

import com.opensab.sab.session.vehicle.BodyStyle
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.VehicleProfile
import com.opensab.sab.session.vehicle.defaultVehicleName

/** How many code points of a rider-typed name survive into the stored profile. */
const val VEHICLE_NAME_MAX_CODE_POINTS = 30

data class VehicleProfileDraft(val name: String, val bodyStyle: BodyStyle, val clusterDisplay: ClusterDisplay)

/** Starts the editor from what is already stored, name included. */
fun KnownVehicle.toDraft(): VehicleProfileDraft = VehicleProfileDraft(displayName, bodyStyle, clusterDisplay)

/**
 * Cuts a string to at most [maxCodePoints] code points, walking code points rather than
 * `String.take` so a name ending mid emoji never survives as half a surrogate pair. Shared by the
 * live field (which cuts a paste instead of rejecting it whole) and [toProfile].
 */
fun String.cutToCodePoints(maxCodePoints: Int): String {
    val points = codePoints().limit(maxCodePoints.toLong()).toArray()
    return String(points, 0, points.size)
}

fun VehicleProfileDraft.toProfile(seriesCode: String): VehicleProfile {
    val cut = name.trim().cutToCodePoints(VEHICLE_NAME_MAX_CODE_POINTS)
    return VehicleProfile(cut.ifEmpty { defaultVehicleName(seriesCode) }, bodyStyle, clusterDisplay)
}
