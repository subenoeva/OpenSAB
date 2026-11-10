package com.opensab.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import com.opensab.logging.Log

/**
 * Resolves the appropriate foreground service types bitmask for [SabConnectionService].
 *
 * Ensures compliance with Android 14+ (API 34+) foreground service type constraints,
 * granting location and microphone capabilities when permitted.
 *
 * The type constants are compile-time ints, inlined and gated on [Build.VERSION.SDK_INT] by the
 * caller-supplied `sdkInt`, which lint cannot follow.
 */
@SuppressLint("InlinedApi")
object ForegroundServiceTypeResolver {

    fun resolve(
        hasLocationPermission: Boolean,
        hasMicPermission: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Int {
        if (sdkInt < Build.VERSION_CODES.Q) return 0

        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        if (sdkInt >= Build.VERSION_CODES.R && hasMicPermission) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasLocationPermission) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }

        return types
    }

    /**
     * Runs [start] with [requested] and, if the system refuses it, once more with `connectedDevice`
     * alone. Returns the type that was obtained, which is what the caller must report and publish:
     * logging [requested] after a fallback is how 152 came to be written for a service holding 16.
     */
    fun startWithFallback(requested: Int, start: (Int) -> Unit): Int {
        val fallback = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return try {
            start(requested)
            requested
        } catch (e: Exception) {
            if (requested == fallback) throw e
            Log.w(e, "Foreground service type %d refused, falling back to CONNECTED_DEVICE", requested)
            start(fallback)
            fallback
        }
    }
}

/** What [SabConnectionService] should hold given the permissions granted right now. */
fun Context.wantedForegroundServiceType(): Int = ForegroundServiceTypeResolver.resolve(
    hasLocationPermission = ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED,
    hasMicPermission = ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
)
