package com.opensab.app.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opensab.logging.Log
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.KnownVehicleCodec
import com.opensab.sab.session.vehicle.VehicleProfile
import com.opensab.sab.session.vehicle.VehicleProfileCodec
import com.opensab.sab.session.vehicle.VehicleRepository
import com.opensab.sab.session.vehicle.withProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [VehicleRepository] backed by DataStore Preferences: two JSON entries per bonded motorcycle,
 * identity and rider profile (see [VehicleRepository]'s KDoc for who writes each one).
 *
 * A key per MAC address avoids the read-modify-write of a single blob, so pairing a motorcycle can
 * never clobber another one written concurrently.
 */
class DataStoreVehicleRepository(
    private val dataStore: DataStore<Preferences>
) : VehicleRepository {

    /** Most recently connected motorcycle first, so the UI can lead with the obvious candidate. */
    override val vehiclesFlow: Flow<List<KnownVehicle>> = dataStore.data.map(::decodeVehicles)

    override suspend fun saveVehicle(vehicle: KnownVehicle) {
        dataStore.edit { preferences ->
            preferences[keyFor(vehicle.macAddress)] = KnownVehicleCodec.encode(vehicle)
        }
        Log.d("Persisted vehicle identity for ${vehicle.macAddress}")
    }

    override suspend fun getVehicle(macAddress: String): KnownVehicle? {
        val preferences = dataStore.data.first()
        val raw = preferences[keyFor(macAddress)] ?: return null
        val identity = KnownVehicleCodec.decode(raw) ?: return null
        return identity.withProfile(decodeProfile(preferences, macAddress))
    }

    override suspend fun getAllVehicles(): List<KnownVehicle> = decodeVehicles(dataStore.data.first())

    override suspend fun getProfile(macAddress: String): VehicleProfile? =
        decodeProfile(dataStore.data.first(), macAddress)

    override suspend fun saveProfile(macAddress: String, profile: VehicleProfile) {
        dataStore.edit { preferences ->
            preferences[profileKeyFor(macAddress)] = VehicleProfileCodec.encode(profile)
        }
        Log.d("Persisted rider profile for $macAddress")
    }

    override suspend fun updateProfile(macAddress: String, profile: VehicleProfile): Boolean {
        var wrote = false
        dataStore.edit { preferences ->
            if (preferences[keyFor(macAddress)] != null) {
                preferences[profileKeyFor(macAddress)] = VehicleProfileCodec.encode(profile)
                wrote = true
            }
        }
        return wrote
    }

    private fun decodeVehicles(preferences: Preferences): List<KnownVehicle> =
        preferences.asMap()
            .filterKeys { it.name.startsWith(KEY_PREFIX) && !it.name.startsWith(PROFILE_PREFIX) }
            .entries
            .mapNotNull { (key, value) -> (value as? String)?.let { key.name to it } }
            .mapNotNull { (keyName, raw) ->
                KnownVehicleCodec.decode(raw)?.let { identity ->
                    val macAddress = keyName.removePrefix(KEY_PREFIX)
                    identity.withProfile(decodeProfile(preferences, macAddress))
                }
            }
            .sortedByDescending { it.lastConnectedTimestamp }

    private fun decodeProfile(preferences: Preferences, macAddress: String): VehicleProfile? =
        preferences[profileKeyFor(macAddress)]?.let(VehicleProfileCodec::decode)

    override suspend fun removeVehicle(macAddress: String) {
        dataStore.edit { preferences ->
            preferences.remove(keyFor(macAddress))
            preferences.remove(profileKeyFor(macAddress))
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            val ownKeys = preferences.asMap().keys.filter { it.name.startsWith(KEY_PREFIX) }
            ownKeys.forEach { preferences.remove(stringPreferencesKey(it.name)) }
        }
    }

    private fun keyFor(macAddress: String) = stringPreferencesKey("$KEY_PREFIX$macAddress")
    private fun profileKeyFor(macAddress: String) = stringPreferencesKey("$PROFILE_PREFIX$macAddress")

    private companion object {
        const val KEY_PREFIX = "vehicle_"
        const val PROFILE_PREFIX = "vehicle_profile_"
    }
}
