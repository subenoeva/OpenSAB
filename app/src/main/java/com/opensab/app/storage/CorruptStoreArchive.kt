package com.opensab.app.storage

import java.io.File
import com.opensab.logging.Log

/**
 * Keeps the bytes of a DataStore file that is about to be thrown away.
 *
 * `AppModule` builds the one `DataStore<Preferences>` of the app with
 * `ReplaceFileCorruptionHandler { emptyPreferences() }`, and that handler is **total**: a single
 * `CorruptionException` drops the settings, every known motorcycle, the favourite player and the
 * weather cache together, because they all share one file. Replacing an unreadable store is the
 * right call — the alternative is an app that will not launch — but doing it in silence is not:
 * all that is left to diagnose it from is a file that shrank.
 *
 * So the file is copied aside first. The copy is never read back automatically and nothing
 * migrates from it; it exists so the next session can say **what** was lost and **why** it would
 * not parse.
 */
object CorruptStoreArchive {

    /**
     * Copies [store] next to itself, stamped with [nowMillis], and returns the copy.
     *
     * Best-effort by contract: a store that is not there, or a disk that will not take the copy,
     * returns null. This runs while the app is starting up and inside DataStore's own corruption
     * path, so it is never the reason a launch fails — losing the archive is bad, losing the launch
     * is worse.
     */
    fun preserve(store: File, nowMillis: Long): File? {
        if (!store.isFile) {
            Log.w("Nothing to archive: %s is not there", store.name)
            return null
        }

        val archive = File(store.parentFile, "${store.name}.corrupt-$nowMillis")
        return try {
            // Never `overwrite = true`: on a destination that happens to be a directory that
            // deletes it recursively, which is a destructive surprise inside a recovery path. The
            // millisecond stamp makes a genuine collision negligible, and refusing one costs an
            // archive rather than whatever was sitting there.
            store.copyTo(archive)
            Log.e(
                "Corrupt store %s (%d bytes) archived as %s before being replaced with an empty " +
                    "one: settings, known motorcycles, favourite player and weather cache are gone",
                store.name,
                store.length(),
                archive.name
            )
            archive
        } catch (e: Exception) {
            Log.e(e, "Could not archive the corrupt store %s; it is being lost", store.name)
            null
        }
    }
}
