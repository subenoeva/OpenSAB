package com.opensab.app.ui

import android.Manifest
import com.opensab.app.ui.permissions.PermissionCatalog
import com.opensab.app.ui.permissions.PermissionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * `RECORD_AUDIO` is asked for from the permissions screen and never at start-up: permissions are
 * not granted from a handlebar, and a system dialog at 90 km/h is worse than a "no". A "no" breaks
 * nothing: the recognizer raises `NO_PERMISSION`, a first-class value of `RecognitionFailure`, and
 * the reply page paints it as a line.
 */
class DictationPermissionTest {

    private fun permissionsOf(id: PermissionId, sdkInt: Int = 36): List<String> =
        PermissionCatalog.entriesFor(sdkInt).single { it.id == id }.permissions

    @Test
    fun `asks for the microphone and for nothing else`() {
        assertEquals(
            listOf(Manifest.permission.RECORD_AUDIO),
            permissionsOf(PermissionId.MICROPHONE)
        )
    }

    /**
     * Kept apart from the BLE and weather sets, the way the weather one is kept apart from BLE: a
     * denied microphone must never stand between the rider and a connection.
     */
    @Test
    fun `shares nothing with the permissions the connection needs`() {
        val connecting = permissionsOf(PermissionId.BLUETOOTH).toSet() +
            permissionsOf(PermissionId.LOCATION_WEATHER).toSet()

        assertEquals(
            emptySet<String>(),
            permissionsOf(PermissionId.MICROPHONE).toSet() intersect connecting
        )
    }

    /** The manifest has to declare it, or the request from Settings is refused before it is shown. */
    @Test
    fun `is declared in the manifest`() {
        val manifest = findManifest().readText()

        assertEquals(
            "RECORD_AUDIO must be declared exactly once",
            1,
            Regex("""android\.permission\.RECORD_AUDIO""").findAll(manifest).count()
        )
    }

    /**
     * And it must not be capped at an API level. `ACCESS_FINE_LOCATION` carries a
     * `maxSdkVersion="30"` because it belongs to the legacy BLE scan; a microphone capped the same
     * way would leave every modern phone unable to dictate, with nothing in the logs to say why.
     */
    @Test
    fun `is not capped at an api level`() {
        val line = findManifest().readLines()
            .single { it.contains("android.permission.RECORD_AUDIO") }

        assertFalse("a capped microphone is a microphone nobody can use", line.contains("maxSdkVersion"))
    }

    private fun findManifest(): File {
        var curr: File? = File(".").canonicalFile
        while (curr != null) {
            File(curr, "src/main/AndroidManifest.xml").takeIf { it.isFile }?.let { return it }
            File(curr, "app/src/main/AndroidManifest.xml").takeIf { it.isFile }?.let { return it }
            curr = curr.parentFile
        }
        error("Could not locate the manifest from ${File(".").canonicalPath}")
    }
}
