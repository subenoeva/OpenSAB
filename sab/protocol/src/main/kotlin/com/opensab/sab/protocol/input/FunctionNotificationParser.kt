package com.opensab.sab.protocol.input

import com.opensab.sab.protocol.assignment.FunctionId

object FunctionNotificationParser {

    fun parse(payload: ByteArray?): HandlebarEvent? {
        if (payload == null || payload.isEmpty()) return null

        return when (val code = payload[0].toInt() and 0xFF) {
            in 1..8 -> {
                val func = FunctionId.fromValue(code) ?: return null
                HandlebarEvent.ButtonPressed(func)
            }
            9 -> HandlebarEvent.ModeChanged(DashboardMode.SAB_MODE)
            10 -> HandlebarEvent.ModeChanged(DashboardMode.HU_MODE)
            else -> null
        }
    }
}
