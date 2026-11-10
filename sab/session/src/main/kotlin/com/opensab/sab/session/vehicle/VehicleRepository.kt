package com.opensab.sab.session.vehicle

import kotlinx.coroutines.flow.Flow

/**
 * Two keys per motorcycle, written by different owners: the profile key is written only by
 * [saveProfile]/[updateProfile], which only the UI calls (in release builds; the debug-only
 * `DebugVehicleSeedReceiver` also writes both keys, to seed a vehicle without a motorcycle); the
 * identity key only by [saveVehicle], which only `KnownVehicleParticipant` calls.
 */
interface VehicleRepository {

    /**
     * Reactive stream of stored profiles, most recently connected first, re-emitting on every
     * write. Callers observe this instead of polling [getAllVehicles] after each mutation.
     */
    val vehiclesFlow: Flow<List<KnownVehicle>>

    suspend fun saveVehicle(vehicle: KnownVehicle)
    suspend fun getVehicle(macAddress: String): KnownVehicle?
    suspend fun getAllVehicles(): List<KnownVehicle>
    suspend fun removeVehicle(macAddress: String)
    suspend fun clear()

    suspend fun getProfile(macAddress: String): VehicleProfile?
    suspend fun saveProfile(macAddress: String, profile: VehicleProfile)

    /** Writes only when the identity record exists; returns whether it wrote. */
    suspend fun updateProfile(macAddress: String, profile: VehicleProfile): Boolean
}
