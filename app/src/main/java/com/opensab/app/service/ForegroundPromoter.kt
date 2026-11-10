package com.opensab.app.service

import com.opensab.logging.Log

/**
 * Upgrades a live session's foreground service type at the one moment Android allows it: while the
 * app is visible. A service started from the ACL receiver with the screen off holds
 * `connectedDevice` alone; asking it to call `startForeground` again from here gets it the
 * microphone, which then stays with it in the background until it stops.
 *
 * It never opens a session: with no service in the foreground [ForegroundServiceGrant] has nothing
 * to promote. [wantedType] is what the permissions allow now, so a microphone the rider has not
 * granted is never asked for.
 */
class ForegroundPromoter(
    private val grant: ForegroundServiceGrant,
    private val wantedType: () -> Int,
    private val requestPromotion: () -> Unit
) {

    fun onUiVisible() {
        val wanted = wantedType()
        if (!grant.needsPromotion(wanted)) return
        Log.i("UI visible over a service holding type %s of %d, asking it to promote", grant.type.value, wanted)
        requestPromotion()
    }
}
