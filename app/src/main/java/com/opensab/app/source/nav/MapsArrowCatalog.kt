package com.opensab.app.source.nav

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.createBitmap
import com.opensab.domain.nav.ArrowBits
import com.opensab.domain.nav.GoogleMapsArrows
import com.opensab.logging.Log

/**
 * The reference arrows, drawn from the Google Maps installed on this phone, so they
 * always match the version that posts the notifications. **Owner of the reference table**: built on
 * first use and rebuilt when Maps' version code changes; nothing else holds a copy.
 * Called only from the notification listener's main thread.
 */
class MapsArrowCatalog(private val context: Context) {

    private var builtFor: Long? = null
    private var table: Map<String, ArrowBits> = emptyMap()

    fun references(): Map<String, ArrowBits> {
        val version = mapsVersion() ?: return emptyMap()
        if (version.first != builtFor) {
            table = build(version.second)
            builtFor = version.first
        }
        return table
    }

    /** The same rasterisation as the references: a 126×126 ARGB canvas. */
    fun arrowOf(drawable: Drawable): ArrowBits? {
        val bitmap = createBitmap(SIZE, SIZE)
        drawable.setBounds(0, 0, SIZE, SIZE)
        drawable.draw(Canvas(bitmap))
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        return ArrowBits.of(SIZE, SIZE, pixels)
    }

    private fun build(versionName: String): Map<String, ArrowBits> {
        val resources = context.packageManager.getResourcesForApplication(MAPS)
        val missing = mutableListOf<String>()
        val built = GoogleMapsArrows.byDrawable.keys.sorted().mapNotNull { name ->
            @Suppress("DiscouragedApi")
            val id = resources.getIdentifier(name, "drawable", MAPS)
            val bits = if (id == 0) null else try {
                resources.getDrawable(id, null)?.let(::arrowOf)
            } catch (e: Exception) {
                Log.w(e, "Maps arrow %s could not be drawn", name)
                null
            }
            if (bits == null) missing += name
            bits?.let { name to it }
        }.toMap()
        Log.i(
            "Maps arrow catalog: %d/%d drawables (Maps %s)",
            built.size,
            GoogleMapsArrows.byDrawable.size,
            versionName
        )
        if (missing.isNotEmpty()) Log.w("Maps arrow missing: %s", missing.joinToString())
        return built
    }

    private fun mapsVersion(): Pair<Long, String>? = try {
        val info = context.packageManager.getPackageInfo(MAPS, 0)
        PackageInfoCompat.getLongVersionCode(info) to (info.versionName ?: "?")
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        const val MAPS = "com.google.android.apps.maps"
        const val SIZE = 126
    }
}
