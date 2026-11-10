package com.opensab.app.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: the ACL receiver and `SabConnectionService` are what
 * auto-connect is made of, and a phone free to doze through both makes the motorcycle look broken.
 * A "no" costs auto-connect and nothing else.
 */
class PermissionsManifestTest {

    @Test
    fun `should declare the battery exemption exactly once`() {
        val manifest = findManifest().readText()

        assertEquals(
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS must be declared exactly once",
            1,
            Regex("""android\.permission\.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS""")
                .findAll(manifest)
                .count()
        )
    }

    @Test
    fun `should declare the system alert window permission exactly once`() {
        val manifest = findManifest().readText()

        assertEquals(
            "SYSTEM_ALERT_WINDOW must be declared exactly once",
            1,
            Regex("""android\.permission\.SYSTEM_ALERT_WINDOW""")
                .findAll(manifest)
                .count()
        )
    }

    /**
     * And it must not be capped at an API level. `ACCESS_FINE_LOCATION` carries a
     * `maxSdkVersion="30"` because it belongs to the legacy BLE scan; a battery exemption capped
     * the same way would let the system doze the connection on every modern phone, with nothing in
     * the logs to say why.
     */
    @Test
    fun `should not cap the battery exemption at an api level`() {
        val line = findManifest().readLines()
            .single { it.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS") }

        assertFalse("a capped exemption is an exemption that expires", line.contains("maxSdkVersion"))
    }

    @Test
    fun `should declare the send sms permission exactly once`() {
        val manifest = findManifest().readText()

        assertEquals(
            "SEND_SMS must be declared exactly once",
            1,
            Regex("""android\.permission\.SEND_SMS""")
                .findAll(manifest)
                .count()
        )
    }

    @Test
    fun `should not cap the send sms permission at an api level`() {
        val line = findManifest().readLines()
            .single { it.contains("android.permission.SEND_SMS") }

        assertFalse("a capped send sms permission expires", line.contains("maxSdkVersion"))
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
