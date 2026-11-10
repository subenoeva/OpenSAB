package com.opensab.app.ui.common

import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.KnownVehicle

/** Which cluster the rider declared, as the garage badges it. */
enum class ClusterBadge { COLOR, SEGMENT }

/** The composables read this and never the domain entity. */
data class VehicleCardUi(
    val title: String,
    val clusterBadge: ClusterBadge,
    val seriesCode: String?
)

fun KnownVehicle.toCardUi(): VehicleCardUi = VehicleCardUi(
    title = displayName,
    clusterBadge = when (clusterDisplay) {
        ClusterDisplay.COLOR -> ClusterBadge.COLOR
        ClusterDisplay.SEGMENT -> ClusterBadge.SEGMENT
    },
    seriesCode = vehicleInfo.seriesCode.trim().takeIf { it.isNotEmpty() }
)
