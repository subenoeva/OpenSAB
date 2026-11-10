package com.opensab.domain.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SabModeFallbackSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `should ship with both SAB_MODE fallbacks off`() {
        // Both change what is written to the bike before the cluster hands the screen over.
        assertFalse(AppSettings().sabModeRerequestOnHuMode)
        assertFalse(AppSettings().projectWithoutSabModeGrant)
    }

    @Test
    fun `should keep both fallbacks off for a settings blob written before they existed`() {
        val old = """{"autoConnect":true,"autoDisconnect":true,"ctsClockSync":true}"""

        val settings = json.decodeFromString<AppSettings>(old)

        assertFalse(settings.sabModeRerequestOnHuMode)
        assertFalse(settings.projectWithoutSabModeGrant)
    }

    @Test
    fun `should keep a fallback the rider turned on across a round trip`() {
        val chosen = AppSettings(sabModeRerequestOnHuMode = true, projectWithoutSabModeGrant = true)

        val restored = json.decodeFromString<AppSettings>(json.encodeToString(AppSettings.serializer(), chosen))

        assertTrue(restored.sabModeRerequestOnHuMode)
        assertTrue(restored.projectWithoutSabModeGrant)
    }
}
