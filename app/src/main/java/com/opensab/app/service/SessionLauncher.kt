package com.opensab.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.opensab.sab.session.SessionOrigin

/**
 * The single door into a session: the scanner, the dashboard and the ACL
 * receiver all go through this, never [com.opensab.sab.session.SabSessionController] directly.
 */
interface SessionLauncher {
    fun start(macAddress: String, origin: SessionOrigin)
    fun stop(origin: SessionOrigin)
}

class AndroidSessionLauncher(private val context: Context) : SessionLauncher {

    override fun start(macAddress: String, origin: SessionOrigin) {
        val intent = Intent(context, SabConnectionService::class.java).apply {
            action = SabConnectionService.ACTION_START_CONNECT
            putExtra(SabConnectionService.EXTRA_MAC_ADDRESS, macAddress)
            putExtra(SabConnectionService.EXTRA_ORIGIN, origin.name)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stop(origin: SessionOrigin) {
        val intent = Intent(context, SabConnectionService::class.java).apply {
            action = SabConnectionService.ACTION_DISCONNECT
            putExtra(SabConnectionService.EXTRA_ORIGIN, origin.name)
        }
        context.startService(intent)
    }
}
