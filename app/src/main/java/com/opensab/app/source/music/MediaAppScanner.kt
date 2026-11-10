package com.opensab.app.source.music

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.opensab.domain.music.MediaAppInfo
import com.opensab.domain.music.MediaPackageFilter

interface MediaAppScanner {
    fun getInstalledMediaApps(): List<MediaAppInfo>
}

/**
 * Whether [packageName] can have its library walked, judged from what the scan found.
 *
 * [AndroidMediaAppScanner] reports three kinds of app under one type: those resolved from a
 * `MediaBrowserService`, which carry a [MediaAppInfo.serviceClassName]; those found only through a
 * launcher activity in the music category; and those found only by checking whether a curated
 * package is installed. The last two are players, but nothing has shown they answer a browse
 * connection, so offering a picker for them would open a dialog that can never load.
 *
 * The [MediaAppScannerDefaults] fallback mirrors [AndroidMediaLibraryBrowser.connect], which tries
 * the known-player table before asking the package manager: if the browser will find a component,
 * the row has to appear, or settings would hide a picker that works.
 */
fun List<MediaAppInfo>.exposesBrowseTree(packageName: String?): Boolean {
    if (packageName == null) return false
    val app = firstOrNull { it.packageName == packageName } ?: return false
    return app.serviceClassName != null ||
        MediaAppScannerDefaults.resolveServiceComponent(packageName) != null
}

class AndroidMediaAppScanner(
    private val context: Context
) : MediaAppScanner {

    override fun getInstalledMediaApps(): List<MediaAppInfo> {
        val pm = context.packageManager
        val seenPackages = mutableSetOf<String>()
        val result = mutableListOf<MediaAppInfo>()

        // 1. Query MediaBrowserService implementations
        val browserIntent = Intent("android.media.browse.MediaBrowserService")
        val browserServices: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(browserIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(browserIntent, 0)
        }

        for (info in browserServices) {
            val serviceInfo = info.serviceInfo ?: continue
            val pkg = serviceInfo.packageName
            if (pkg == context.packageName || MediaPackageFilter.isBlacklisted(pkg)) continue // Skip OpenSAB & non-music apps
            if (seenPackages.add(pkg)) {
                val appName = info.loadLabel(pm).toString().ifBlank { pkg }
                result.add(
                    MediaAppInfo(
                        packageName = pkg,
                        appName = appName,
                        serviceClassName = serviceInfo.name
                    )
                )
            }
        }

        // 2. Query Music Category apps
        val musicAppIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
        val musicApps: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(musicAppIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(musicAppIntent, 0)
        }

        for (info in musicApps) {
            val activityInfo = info.activityInfo ?: continue
            val pkg = activityInfo.packageName
            if (pkg == context.packageName || MediaPackageFilter.isBlacklisted(pkg)) continue // Skip OpenSAB & non-music apps
            if (seenPackages.add(pkg)) {
                val appName = info.loadLabel(pm).toString().ifBlank { pkg }
                result.add(
                    MediaAppInfo(
                        packageName = pkg,
                        appName = appName,
                        serviceClassName = null
                    )
                )
            }
        }

        // 3. Known modded / popular music packages direct check
        for (pkg in MediaPackageFilter.CURATED_MUSIC_PACKAGES) {
            if (seenPackages.contains(pkg)) continue
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                val appName = pm.getApplicationLabel(appInfo).toString().ifBlank { pkg }
                seenPackages.add(pkg)
                result.add(
                    MediaAppInfo(
                        packageName = pkg,
                        appName = appName,
                        serviceClassName = null
                    )
                )
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed on device, skip
            }
        }

        return result.sortedBy { it.appName.lowercase() }
    }
}
