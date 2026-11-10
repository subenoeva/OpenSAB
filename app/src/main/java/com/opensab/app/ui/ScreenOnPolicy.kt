package com.opensab.app.ui

import com.opensab.domain.settings.AppSettings
import com.opensab.sab.session.SabSessionState

object ScreenOnPolicy {

    fun shouldKeepScreenOn(
        settings: AppSettings,
        sessionState: SabSessionState
    ): Boolean = settings.keepScreenOnWhileRiding && sessionState is SabSessionState.Connected

    fun shouldKeepScreenOn(
        keepScreenOnWhileRiding: Boolean,
        isConnected: Boolean
    ): Boolean = keepScreenOnWhileRiding && isConnected

    fun showWhenLocked(settings: AppSettings): Boolean = settings.showOnLockScreen

}
