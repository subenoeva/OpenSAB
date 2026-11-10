package com.opensab.sab.session.vehicle

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/**
 * The backing maps are [MutableStateFlow]s so they can serve [vehiclesFlow]; `update` keeps every
 * read-modify-write atomic. Identity and profile live in separate maps, mirroring the two keys [com.opensab.app.persistence.DataStoreVehicleRepository]
 * writes.
 */
class InMemoryVehicleRepository : VehicleRepository {

    private val storage = MutableStateFlow<Map<String, KnownVehicle>>(emptyMap())
    private val profiles = MutableStateFlow<Map<String, VehicleProfile>>(emptyMap())

    override val vehiclesFlow: Flow<List<KnownVehicle>> =
        combine(storage, profiles) { identities, profiles ->
            identities.mapValues { (mac, vehicle) -> vehicle.withProfile(profiles[mac]) }.sortedForDisplay()
        }

    override suspend fun saveVehicle(vehicle: KnownVehicle) {
        storage.update { it + (vehicle.macAddress to vehicle) }
    }

    override suspend fun getVehicle(macAddress: String): KnownVehicle? {
        return storage.value[macAddress]?.withProfile(profiles.value[macAddress])
    }

    override suspend fun getAllVehicles(): List<KnownVehicle> {
        return storage.value.mapValues { (mac, vehicle) -> vehicle.withProfile(profiles.value[mac]) }
            .sortedForDisplay()
    }

    override suspend fun removeVehicle(macAddress: String) {
        storage.update { it - macAddress }
        profiles.update { it - macAddress }
    }

    override suspend fun clear() {
        storage.value = emptyMap()
        profiles.value = emptyMap()
    }

    override suspend fun getProfile(macAddress: String): VehicleProfile? {
        return profiles.value[macAddress]
    }

    override suspend fun saveProfile(macAddress: String, profile: VehicleProfile) {
        profiles.update { it + (macAddress to profile) }
    }

    override suspend fun updateProfile(macAddress: String, profile: VehicleProfile): Boolean {
        if (storage.value[macAddress] == null) return false
        saveProfile(macAddress, profile)
        return true
    }

    /**
     * Matches the ordering [com.opensab.app.persistence.DataStoreVehicleRepository] guarantees, so the
     * fake is not kinder.
     */
    private fun Map<String, KnownVehicle>.sortedForDisplay(): List<KnownVehicle> =
        values.sortedByDescending { it.lastConnectedTimestamp }
}
