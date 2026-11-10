package com.opensab.app.ui.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case passes `sdkInt` explicitly: `Build.VERSION.SDK_INT` is `0` on a JVM test, so a case
 * that let the value default would be asserting against a phone that does not exist.
 */
class PermissionCatalogTest {

    /** The two cuts that exist (31 for BLE, 33 for notifications), the floor and the ceiling. */
    private val apiLevels = listOf(26, 30, 31, 33, 37)

    private fun entry(sdkInt: Int, id: PermissionId): PermissionEntry? =
        PermissionCatalog.entriesFor(sdkInt).firstOrNull { it.id == id }

    private fun require(sdkInt: Int, id: PermissionId): PermissionEntry =
        checkNotNull(entry(sdkInt, id)) { "no $id row at API $sdkInt" }

    @Test
    fun `should ask for the dedicated bluetooth permissions from api 31`() {
        for (sdkInt in listOf(31, 33, 37)) {
            val bluetooth = require(sdkInt, PermissionId.BLUETOOTH)

            assertTrue("API $sdkInt", Manifest.permission.BLUETOOTH_SCAN in bluetooth.permissions)
            assertTrue("API $sdkInt", Manifest.permission.BLUETOOTH_CONNECT in bluetooth.permissions)
            assertFalse(
                "fine location is no longer needed for BLE discovery on API $sdkInt",
                Manifest.permission.ACCESS_FINE_LOCATION in bluetooth.permissions
            )
        }
    }

    @Test
    fun `should fall back to fine location for discovery below api 31`() {
        for (sdkInt in listOf(26, 30)) {
            val bluetooth = require(sdkInt, PermissionId.BLUETOOTH)

            assertEquals(
                "API $sdkInt",
                listOf(Manifest.permission.ACCESS_FINE_LOCATION),
                bluetooth.permissions
            )
        }
    }

    @Test
    fun `should not show a notifications row before api 33`() {
        for (sdkInt in listOf(26, 30, 31)) {
            assertNull("API $sdkInt", entry(sdkInt, PermissionId.NOTIFICATIONS))
        }
        for (sdkInt in listOf(33, 37)) {
            assertEquals(
                "API $sdkInt",
                listOf(Manifest.permission.POST_NOTIFICATIONS),
                require(sdkInt, PermissionId.NOTIFICATIONS).permissions
            )
        }
    }

    /**
     * The order is the order of the screen: first what stops the connection, then what holds up the
     * most features, and the incidental last. A missing row closes the gap; it does not shift the
     * rest around.
     */
    @Test
    fun `should keep the row order stable on every api level`() {
        val full = listOf(
            PermissionId.BLUETOOTH,
            PermissionId.BLUETOOTH_ENABLED,
            PermissionId.NOTIFICATION_ACCESS,
            PermissionId.BATTERY_UNRESTRICTED,
            PermissionId.DRAW_OVERLAYS,
            PermissionId.NOTIFICATIONS,
            PermissionId.LOCATION_WEATHER,
            PermissionId.LOCATION_SERVICES,
            PermissionId.MICROPHONE,
            PermissionId.CONTACTS,
            PermissionId.PLACE_CALLS,
            PermissionId.ANSWER_CALLS,
            PermissionId.SEND_SMS
        )

        for (sdkInt in apiLevels) {
            val expected =
                if (sdkInt >= 33) full else full.filterNot { it == PermissionId.NOTIFICATIONS }

            assertEquals("API $sdkInt", expected, PermissionCatalog.entriesFor(sdkInt).map { it.id })
        }
    }

    @Test
    fun `should show twelve rows on android 12 and thirteen from android 13`() {
        for (sdkInt in listOf(26, 30, 31)) {
            assertEquals("API $sdkInt", 12, PermissionCatalog.entriesFor(sdkInt).size)
        }
        for (sdkInt in listOf(33, 37)) {
            assertEquals("API $sdkInt", 13, PermissionCatalog.entriesFor(sdkInt).size)
        }
    }

    @Test
    fun `should keep dialling by voice independent of the api level`() {
        for (sdkInt in 26..35) {
            assertEquals(
                listOf("android.permission.READ_CONTACTS"),
                require(sdkInt, PermissionId.CONTACTS).permissions
            )
            assertEquals(
                listOf("android.permission.CALL_PHONE"),
                require(sdkInt, PermissionId.PLACE_CALLS).permissions
            )
        }
    }

    /**
     * A couple of kilometres is exact enough for a forecast, and fine location is a much bigger ask
     * for nothing in return. `WeatherPermissionTest` guards the same regression.
     */
    @Test
    fun `should never ask weather for fine location`() {
        for (sdkInt in apiLevels) {
            assertEquals(
                "API $sdkInt",
                listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                require(sdkInt, PermissionId.LOCATION_WEATHER).permissions
            )
        }
    }

    @Test
    fun `should keep the microphone independent of the api level`() {
        for (sdkInt in apiLevels) {
            assertEquals(
                "API $sdkInt",
                listOf(Manifest.permission.RECORD_AUDIO),
                require(sdkInt, PermissionId.MICROPHONE).permissions
            )
        }
    }

    /**
     * A special access is not granted by a dialog and a system toggle is not granted on anyone's
     * behalf, so neither has anything to launch.
     */
    @Test
    fun `should leave the launchable set empty for everything that is not runtime`() {
        for (sdkInt in apiLevels) {
            val notRuntime = PermissionCatalog.entriesFor(sdkInt)
                .filter { it.kind != PermissionKind.RUNTIME }

            assertEquals("API $sdkInt", 5, notRuntime.size)
            assertEquals(
                "API $sdkInt",
                emptyList<PermissionId>(),
                notRuntime.filter { it.permissions.isNotEmpty() }.map { it.id }
            )
        }
    }

    @Test
    fun `should mark only bluetooth and its radio as essential`() {
        for (sdkInt in apiLevels) {
            assertEquals(
                "API $sdkInt",
                listOf(PermissionId.BLUETOOTH, PermissionId.BLUETOOTH_ENABLED),
                PermissionCatalog.entriesFor(sdkInt).filter { it.essential }.map { it.id }
            )
        }
    }

    @Test
    fun `should give every id at most one row`() {
        for (sdkInt in apiLevels) {
            val ids = PermissionCatalog.entriesFor(sdkInt).map { it.id }

            assertEquals("API $sdkInt", ids.distinct(), ids)
        }
    }

    @Test
    fun `should include send sms as runtime permission on api 34`() {
        val entry = require(34, PermissionId.SEND_SMS)
        assertEquals(PermissionKind.RUNTIME, entry.kind)
        assertEquals(listOf(Manifest.permission.SEND_SMS), entry.permissions)
        assertFalse("send sms is not essential", entry.essential)
    }
}

