package com.opensab.app.source.music

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * Notification-listener access, the permission that unlocks reading other apps' media sessions.
 *
 * It cannot be requested with a runtime dialog: the rider has to enable it in system Settings.
 */
object MediaAccess {

    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
}
