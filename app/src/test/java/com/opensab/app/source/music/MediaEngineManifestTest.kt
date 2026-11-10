package com.opensab.app.source.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaEngineManifestTest {

    @Test
    fun shouldDeclareWakeLockPermissionExactlyOnce() {
        val manifest = findManifest().readText()
        val count = Regex("""android\.permission\.WAKE_LOCK""").findAll(manifest).count()
        assertEquals("android.permission.WAKE_LOCK must be declared exactly once", 1, count)
    }

    @Test
    fun shouldNotCapWakeLockPermissionAtMaxSdkVersion() {
        val line = findManifest().readLines()
            .single { it.contains("android.permission.WAKE_LOCK") }
        assertFalse("WAKE_LOCK must remain active across all Android versions", line.contains("maxSdkVersion"))
    }

    @Test
    fun shouldNotShipTheDebugReceiverInTheProductionManifest() {
        val manifest = findManifest().readText()
        assertFalse(
            "MediaPlaybackDebugReceiver is a bench tool; it belongs to src/debug",
            manifest.contains("MediaPlaybackDebugReceiver")
        )
        assertFalse(
            "The debug broadcast actions must not be declared in production",
            manifest.contains("com.opensab.debug.")
        )
    }

    @Test
    fun shouldDeclareTheDebugReceiverInTheDebugManifest() {
        val srcDir = checkNotNull(findManifest().parentFile?.parentFile)
        val debugManifest = File(srcDir, "debug/AndroidManifest.xml")
        assertTrue("src/debug/AndroidManifest.xml must exist", debugManifest.isFile)

        val text = debugManifest.readText()
        assertTrue("MediaPlaybackDebugReceiver must be declared", text.contains("MediaPlaybackDebugReceiver"))
        assertTrue("Must contain action com.opensab.debug.TRIGGER_PLAY", text.contains("com.opensab.debug.TRIGGER_PLAY"))
        assertTrue("Must stay behind the DUMP permission", text.contains("android.permission.DUMP"))
    }

    @Test
    fun shouldDocumentExplicitPackageFlagForDebugBroadcasts() {
        val srcDir = checkNotNull(findManifest().parentFile?.parentFile)
        val debugManifest = File(srcDir, "debug/AndroidManifest.xml")
        val text = debugManifest.readText()
        assertTrue(
            "Debug manifest comments must specify -p com.opensab to avoid BAL/implicit broadcast drops",
            text.contains("adb shell am broadcast -p com.opensab -a com.opensab.debug.TRIGGER_PLAY")
        )
    }

    private fun findManifest(): File {
        var curr: File? = File(".").canonicalFile
        while (curr != null) {
            File(curr, "src/main/AndroidManifest.xml").takeIf { it.isFile }?.let { return it }
            File(curr, "app/src/main/AndroidManifest.xml").takeIf { it.isFile }?.let { return it }
            curr = curr.parentFile
        }
        error("Could not locate AndroidManifest.xml from ${File(".").canonicalPath}")
    }
}
