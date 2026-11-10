package com.opensab.sab.session.vehicle

import com.opensab.logging.Log
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the rider says the cluster is. Nothing over the link reports it. */
enum class ClusterDisplay(val code: Int) {
    COLOR(0), SEGMENT(1);
    companion object {
        fun fromCode(code: Int?): ClusterDisplay = entries.firstOrNull { it.code == code } ?: COLOR
    }
}

enum class BodyStyle(val code: Int) {
    GENERIC(0), NAKED(1), SPORT(2), ADVENTURE(3), TOURING(4), CUSTOM(5), SCOOTER(6);
    companion object {
        fun fromCode(code: Int?): BodyStyle = entries.firstOrNull { it.code == code } ?: GENERIC
    }
}

/** The rider's half of a vehicle. A null [name] keeps whatever name the identity record carries. */
data class VehicleProfile(
    val name: String? = null,
    val bodyStyle: BodyStyle = BodyStyle.GENERIC,
    val clusterDisplay: ClusterDisplay = ClusterDisplay.COLOR
)

object VehicleProfileCodec {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Dto(val name: String? = null, val bodyStyle: Int? = null, val clusterDisplay: Int? = null)

    fun encode(profile: VehicleProfile): String = json.encodeToString(
        Dto(profile.name, profile.bodyStyle.code, profile.clusterDisplay.code)
    )

    /** Returns [VehicleProfile] defaults when the stored record is unreadable. */
    fun decode(raw: String): VehicleProfile = runCatchingCancellable {
        json.decodeFromString<Dto>(raw).let {
            VehicleProfile(it.name, BodyStyle.fromCode(it.bodyStyle), ClusterDisplay.fromCode(it.clusterDisplay))
        }
    }.getOrElse { cause ->
        Log.w(cause, "Unreadable vehicle profile, using defaults")
        VehicleProfile()
    }
}

/** Applies a stored profile on top of an identity read. */
fun KnownVehicle.withProfile(profile: VehicleProfile?): KnownVehicle =
    if (profile == null) this
    else copy(
        displayName = profile.name ?: displayName,
        bodyStyle = profile.bodyStyle,
        clusterDisplay = profile.clusterDisplay
    )
