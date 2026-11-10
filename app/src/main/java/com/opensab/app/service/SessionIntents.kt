package com.opensab.app.service

import com.opensab.sab.session.SessionCommand
import com.opensab.sab.session.SessionOrigin

/**
 * Pure decoding of what [SabConnectionService.onStartCommand] receives, so the service does
 * nothing that cannot be tested here.
 */
object SessionIntents {

    fun decode(action: String?, macAddress: String?, origin: String?, startId: Int): SessionCommand? {
        val decodedOrigin = origin?.let { raw ->
            runCatching { SessionOrigin.valueOf(raw) }.getOrNull()
        } ?: SessionOrigin.RECEIVER

        return when (action) {
            SabConnectionService.ACTION_START_CONNECT -> {
                if (macAddress == null) null
                else SessionCommand.Start(macAddress, decodedOrigin, startId)
            }
            SabConnectionService.ACTION_DISCONNECT -> SessionCommand.Disconnect(decodedOrigin, startId)
            else -> null
        }
    }
}
