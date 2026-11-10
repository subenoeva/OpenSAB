package com.opensab.app.source.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import com.opensab.domain.weather.GeoPosition
import com.opensab.domain.weather.LocationSource
import com.opensab.logging.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [LocationSource] over `LocationManager`, with `Geocoder` for the place name.
 *
 * Fresh (< 60s) last known fix first, or an active fix if stale or absent: avoids static
 * coordinates while in motion without running GPS continuously. Everything degrades to null rather
 * than throwing — no permission, no provider, no fix and no geocoder are all "no fix for now", and
 * the source falls back to the place of its last reading.
 */
class AndroidLocationSource(
    context: Context,
    private val fixTimeout: Duration = 10.seconds
) : LocationSource {

    private val appContext = context.applicationContext

    override suspend fun currentPosition(): GeoPosition? {
        if (!hasCoarseLocationPermission()) {
            Log.d("No coarse location permission, weather stays unavailable")
            return null
        }

        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            Log.w("No LocationManager on this device")
            return null
        }

        val lastKnown = lastKnownLocation(manager)
        val now = System.currentTimeMillis()
        val isFresh = lastKnown != null && (now - lastKnown.time) < 60_000L

        val location = if (isFresh) {
            lastKnown
        } else {
            currentLocation(manager) ?: lastKnown
        }
        if (location == null) {
            Log.d("No location fix available")
            return null
        }

        return GeoPosition(
            latitude = location.latitude,
            longitude = location.longitude,
            locality = localityOf(location)
        )
    }

    private fun hasCoarseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * [LocationManager.GPS_PROVIDER] and [LocationManager.PASSIVE_PROVIDER] require ACCESS_FINE_LOCATION;
     * querying them with only ACCESS_COARSE_LOCATION throws SecurityException on Android 12+ (API 31+).
     */
    private fun allowedProviders(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add("fused")
        }
        add(LocationManager.NETWORK_PROVIDER)
        if (hasFineLocationPermission()) {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(manager: LocationManager): Location? = allowedProviders()
        .mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }
                .onFailure { Log.d(it, "Could not read the last known %s location", provider) }
                .getOrNull()
        }
        .maxByOrNull { it.time }

    /**
     * Asks every enabled provider in turn until one answers. With only the coarse permission,
     * `fused` has been seen answering null in 14 ms while `network` was enabled beside it, and
     * asking only the first provider left the weather blank.
     */
    private suspend fun currentLocation(manager: LocationManager): Location? {
        val providers = allowedProviders().filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) {
            Log.d("No location provider is enabled and permitted")
            return null
        }
        for (provider in providers) {
            val location = currentLocation(manager, provider)
            if (location != null) return location
            Log.d("No current location from %s", provider)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(manager: LocationManager, provider: String): Location? =
        withTimeoutOrNull(fixTimeout) {
            suspendCancellableCoroutine { continuation: CancellableContinuation<Location?> ->
                val executor = Executor { command -> command.run() }
                val consumer = Consumer<Location?> { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                runCatching {
                    LocationManagerCompat.getCurrentLocation(
                        manager,
                        provider,
                        null as CancellationSignal?,
                        executor,
                        consumer
                    )
                }.onFailure { cause ->
                    Log.w(cause, "Could not request a current location fix from %s", provider)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    /**
     * Open-Meteo answers coordinates, not place names, and its geocoding endpoint only works the
     * other way round, so the second line of the page comes from the platform geocoder. Decoration:
     * null just leaves the line empty.
     */
    private suspend fun localityOf(location: Location): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(appContext)
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.onFailure { cause ->
            Log.d(cause, "Reverse geocoding failed, the page keeps its second line empty")
        }.getOrNull()
    }
}
