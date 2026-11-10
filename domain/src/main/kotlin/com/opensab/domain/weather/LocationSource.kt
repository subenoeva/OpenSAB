package com.opensab.domain.weather

/**
 * A fix good enough for weather: coarse coordinates plus a best-effort place name.
 *
 * [locality] is null whenever reverse geocoding is unavailable or finds nothing. It is decoration
 * for the second line of the page, never a precondition.
 */
data class GeoPosition(
    val latitude: Double,
    val longitude: Double,
    val locality: String?
)

/** Where the rider is: an input to the query, never a stored preference. */
interface LocationSource {

    /** null when the permission is missing, no provider is enabled, or no fix arrives in time. */
    suspend fun currentPosition(): GeoPosition?
}
