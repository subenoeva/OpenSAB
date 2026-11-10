package com.opensab.app.source.weather

import com.opensab.domain.weather.HttpGetClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [HttpGetClient] over `HttpURLConnection`: one unauthenticated GET does not justify an HTTP
 * library. Swapping one in later means replacing this class and nothing else.
 */
class JdkHttpGetClient(
    private val connectTimeout: Duration = 10.seconds,
    private val readTimeout: Duration = 10.seconds
) : HttpGetClient {

    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeout.inWholeMilliseconds.toInt()
        connection.readTimeout = readTimeout.inWholeMilliseconds.toInt()
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("HTTP $status for $url")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "OpenSAB/0.1 (github.com/opensab)"
    }
}
