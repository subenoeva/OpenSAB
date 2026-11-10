package com.opensab.domain.weather

/**
 * The one network call this project makes.
 *
 * A port rather than a direct dependency so the refresh policy stays JVM-testable, and so swapping
 * `HttpURLConnection` for a real HTTP stack later touches one class.
 */
interface HttpGetClient {

    /** Body of a 2xx response. Throws on anything else. */
    suspend fun get(url: String): String
}
