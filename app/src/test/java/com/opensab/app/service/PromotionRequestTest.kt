package com.opensab.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PromotionRequestTest {

    /** A PROMOTE that created the service has no session under it and must not keep it alive. */
    @Test
    fun aPromoteWithNoServiceInTheForegroundStopsInsteadOfOpeningAnything() {
        assertEquals(PromotionRequest.StopSelf, PromotionRequest.of(grantedType = null, connected = false))
    }

    @Test
    fun aPromoteOverAConnectingSessionKeepsTheConnectingNotification() {
        assertEquals(PromotionRequest.EnterForeground(connected = false), PromotionRequest.of(grantedType = 0x10, connected = false))
    }

    @Test
    fun aPromoteOverAConnectedSessionKeepsTheConnectedNotification() {
        assertEquals(PromotionRequest.EnterForeground(connected = true), PromotionRequest.of(grantedType = 0x10, connected = true))
    }
}
