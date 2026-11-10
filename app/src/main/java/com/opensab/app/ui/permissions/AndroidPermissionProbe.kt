package com.opensab.app.ui.permissions

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.opensab.app.source.music.MediaAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The only file of `ui/permissions/` that imports `android.*`. Nothing to unit-test on purpose:
 * every decision lives in [DefaultPermissionStatusReader], and this is only platform reads.
 */
class AndroidPermissionProbe @Inject constructor(
    @param:ApplicationContext private val context: Context
) : PermissionProbe {

    override val sdkInt: Int = Build.VERSION.SDK_INT

    override fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Delegates to `MediaAccess` so notification access is looked up in one place only. */
    override fun isNotificationListenerEnabled(): Boolean = MediaAccess.isGranted(context)

    override fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    override fun isBluetoothEnabled(): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    override fun areLocationServicesEnabled(): Boolean =
        context.getSystemService(LocationManager::class.java)
            ?.let { LocationManagerCompat.isLocationEnabled(it) } == true

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)
}
