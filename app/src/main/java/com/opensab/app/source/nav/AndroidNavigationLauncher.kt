package com.opensab.app.source.nav

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.opensab.domain.nav.NavigationApp
import com.opensab.domain.nav.NavigationLaunch
import com.opensab.domain.nav.NavigationLauncher
import com.opensab.domain.nav.NavigationUriBuilder
import com.opensab.logging.Log

/**
 * Uses a PendingIntent with background activity launch (BAL) exemption options:
 * - API 36+ (Baklava): ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
 * - API 34-35 (UpsideDownCake / VanillaIceCream): ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
 * - API 26-33 (Oreo to Tiramisu): standard PendingIntent (BAL inherently permitted prior to Android 14)
 * with a fallback to direct startActivity if PendingIntent fails.
 *
 * Reports whether the keyguard was up at launch: Google Maps cold-started behind it drops the route.
 */
class AndroidNavigationLauncher(
    private val context: Context
) : NavigationLauncher {

    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)

    override fun isAppInstalled(app: NavigationApp): Boolean {
        return try {
            context.packageManager.getPackageInfo(app.packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun launchNavigation(query: String, preferredApp: NavigationApp): NavigationLaunch {
        if (query.isBlank()) {
            Log.w("Cannot launch navigation: blank query")
            return NavigationLaunch.NotStarted
        }

        if (!isAppInstalled(preferredApp)) {
            Log.w("Cannot launch navigation: %s is not installed", preferredApp.name)
            return NavigationLaunch.NotStarted
        }

        val started = NavigationLaunch.Started(deviceLocked = keyguardManager.isKeyguardLocked)

        val uriString = NavigationUriBuilder.buildNavigationUri(query, preferredApp)
        val intent = Intent(Intent.ACTION_VIEW, uriString.toUri()).apply {
            setPackage(preferredApp.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        return try {
            Log.i(
                "Launching guided navigation for '%s' using %s (locked=%s)",
                query,
                preferredApp.name,
                started.deviceLocked
            )
            val optionsBundle = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> {
                    ActivityOptions.makeBasic().apply {
                        pendingIntentBackgroundActivityStartMode =
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    }.toBundle()
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    @Suppress("DEPRECATION")
                    ActivityOptions.makeBasic().apply {
                        pendingIntentBackgroundActivityStartMode =
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }.toBundle()
                }
                else -> null
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent.send(
                context,
                0,
                null,
                null,
                null,
                null,
                optionsBundle
            )
            started
        } catch (e: Exception) {
            Log.e(e, "Error launching navigation via PendingIntent, falling back to direct startActivity")
            try {
                context.startActivity(intent)
                started
            } catch (fallbackError: Exception) {
                Log.e(fallbackError, "Direct startActivity also failed")
                NavigationLaunch.NotStarted
            }
        }
    }
}
