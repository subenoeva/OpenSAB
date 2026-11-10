package com.opensab.sab.session

import com.opensab.sab.session.vehicle.VehicleProfile
import com.opensab.sab.session.vehicle.VehicleRepository

interface SessionAnnouncer {
    fun announceOpened(session: SabSession)
    fun announceClosing(session: SabSession)
}

fun interface StoredVehicleProfileResolver {
    /** The stored profile with its name already resolved, or null for a vehicle never seen. */
    suspend fun resolveStoredProfile(macAddress: String): VehicleProfile?
}

/**
 * The canonical [StoredVehicleProfileResolver] over a [VehicleRepository]: once the vehicle has
 * connected before, [VehicleRepository.getVehicle] already carries the profile merged into the
 * identity with the name resolved, so that wins; otherwise the bare profile answers for a pairing
 * that has not connected yet; otherwise null for a vehicle this repository has never seen.
 *
 * The one implementation every caller shares, so the three branches are proven once instead of
 * copied wherever a [StoredVehicleProfileResolver] is wired.
 */
fun VehicleRepository.storedProfileResolver(): StoredVehicleProfileResolver =
    StoredVehicleProfileResolver { mac ->
        val identity = getVehicle(mac)
        val profile = getProfile(mac)
        when {
            identity != null -> VehicleProfile(identity.displayName, identity.bodyStyle, identity.clusterDisplay)
            profile != null -> profile
            else -> null
        }
    }
