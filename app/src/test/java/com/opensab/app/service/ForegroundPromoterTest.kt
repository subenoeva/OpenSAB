package com.opensab.app.service

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundPromoterTest {

    private val grant = ForegroundServiceGrant(sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private var promotions = 0
    private val promoter = ForegroundPromoter(grant, wantedType = { 0x98 }, requestPromotion = { promotions++ })

    @Test
    fun theUiComingForwardOverAReceiverSessionWithoutMicrophoneAsksTheServiceToPromote() {
        grant.publish(0x10, startedVisible = false)

        promoter.onUiVisible()

        assertEquals(1, promotions)
    }

    @Test
    fun aSessionThatAlreadyHasEveryTypeIsLeftAlone() {
        grant.publish(0x98, startedVisible = true)

        promoter.onUiVisible()

        assertEquals(0, promotions)
    }

    /** Opening the app with no session must not start the service: the promotion never opens one. */
    @Test
    fun withoutAServiceInTheForegroundThereIsNothingToPromote() {
        promoter.onUiVisible()

        assertEquals(0, promotions)
    }

    @Test
    fun aMicrophoneStillDeniedIsNotWantedSoNoPromotionIsAskedForIt() {
        val noMic = ForegroundPromoter(grant, wantedType = { 0x18 }, requestPromotion = { promotions++ })
        grant.publish(0x18, startedVisible = false)

        noMic.onUiVisible()

        assertEquals(0, promotions)
    }
}
