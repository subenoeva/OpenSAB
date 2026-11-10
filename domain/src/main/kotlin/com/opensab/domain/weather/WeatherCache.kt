package com.opensab.domain.weather

/**
 * Last good reading, so the carousel entry is open on the first frame of a session instead of
 * waiting for a fetch that takes a couple of seconds — a garage cycle lasts ten minutes.
 */
interface WeatherCache {

    /** null when nothing was stored, the stored reading is stale, or the stored JSON is corrupt. */
    suspend fun load(): WeatherInfo?

    /**
     * Where the last stored reading was taken, **whatever its age**: the stand-in for a session
     * that starts with no fix. null when nothing was stored or the reading
     * predates the coordinates being saved.
     */
    suspend fun lastPosition(): GeoPosition?

    suspend fun save(info: WeatherInfo)
}
