package com.opensab.app.ui

import com.opensab.app.ui.permissions.PermissionCatalog
import com.opensab.app.ui.permissions.PermissionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Weather location is asked for from its own `LOCATION_WEATHER` row, separate from Bluetooth, and
 * only as the coarse permission.
 */
class WeatherPermissionTest {

    private fun weatherPermissions(sdkInt: Int): List<String> =
        PermissionCatalog.entriesFor(sdkInt)
            .single { it.id == PermissionId.LOCATION_WEATHER }
            .permissions

    @Test
    fun requiresCoarseLocationOnEveryApiLevel() {
        for (sdkInt in listOf(26, 29, 30, 31, 33, 34, 36)) {
            assertEquals(
                "API $sdkInt",
                listOf("android.permission.ACCESS_COARSE_LOCATION"),
                weatherPermissions(sdkInt)
            )
        }
    }

    /**
     * A couple of kilometres is exact enough for a forecast, and fine location is a much bigger ask
     * for nothing in return. The `ACCESS_FINE_LOCATION` already in the manifest is capped at
     * `maxSdkVersion="30"` and belongs to the legacy BLE scan, so it is no help here either.
     */
    @Test
    fun doesNotAskForFineLocationBecauseCoarseIsEnoughForWeather() {
        for (sdkInt in listOf(26, 30, 31, 34)) {
            assertFalse(
                "API $sdkInt",
                "android.permission.ACCESS_FINE_LOCATION" in weatherPermissions(sdkInt)
            )
        }
    }
}
