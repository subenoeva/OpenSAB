package com.opensab.app.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the bootstrap needs from the manifest, which no compiler checks: an activity out of recents,
 * in a task of its own so it never brings the app's task forward over the keyguard, opaque,
 * and the service able to hold the microphone at all.
 */
class BootstrapManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private val manifest by lazy {
        val file = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
    }

    private fun component(tag: String, name: String): Element {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == name }
    }

    @Test
    fun theBootstrapActivityIsPrivateAndOutOfRecents() {
        val activity = component("activity", ".service.SessionBootstrapActivity")

        assertEquals("false", activity.getAttributeNS(android, "exported"))
        assertEquals("true", activity.getAttributeNS(android, "excludeFromRecents"))
        assertEquals("true", activity.getAttributeNS(android, "noHistory"))
        assertEquals("", activity.getAttributeNS(android, "taskAffinity"))
        assertEquals(true, activity.hasAttributeNS(android, "taskAffinity"))
        assertEquals("@style/Theme.OpenSAB.Bootstrap", activity.getAttributeNS(android, "theme"))
    }

    /**
     * A translucent window does not occlude the keyguard, so over a locked phone the activity may
     * never count as visible. An opaque window does.
     */
    @Test
    fun theBootstrapThemeIsOpaque() {
        val themes = listOf(File("src/main/res/values/themes.xml"), File("app/src/main/res/values/themes.xml")).first { it.isFile }
        val styles = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(themes).getElementsByTagName("style")
        val style = (0 until styles.length).map { styles.item(it) as Element }
            .single { it.getAttribute("name") == "Theme.OpenSAB.Bootstrap" }
        val items = style.getElementsByTagName("item")
        val translucent = (0 until items.length).map { items.item(it) as Element }
            .any { it.getAttribute("name") == "android:windowIsTranslucent" && it.textContent.trim() == "true" }

        assertEquals(false, style.getAttribute("parent").contains("Translucent"))
        assertEquals(false, translucent)
    }

    @Test
    fun theServiceDeclaresTheMicrophoneTypeItAsksFor() {
        val service = component("service", ".service.SabConnectionService")

        assertEquals(true, service.getAttributeNS(android, "foregroundServiceType").split('|').contains("microphone"))
    }

    @Test
    fun theOverlayPermissionThatExemptsTheLaunchIsDeclared() {
        val permissions = manifest.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map { (permissions.item(it) as Element).getAttributeNS(android, "name") }

        assertEquals(true, "android.permission.SYSTEM_ALERT_WINDOW" in names)
    }
}
