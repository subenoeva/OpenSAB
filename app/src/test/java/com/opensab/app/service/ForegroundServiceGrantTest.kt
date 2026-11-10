package com.opensab.app.service

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundServiceGrantTest {

    private val connected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    private val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val all = connected or location or microphone

    private fun grant() = ForegroundServiceGrant(sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

    @Test
    fun theGrantedTypeIsWhatTheServicePublishedAndNothingBefore() {
        val grant = grant()
        assertNull(grant.type.value)

        grant.publish(all, startedVisible = true)

        assertEquals(0x98, grant.type.value)
    }

    @Test
    fun theMicrophoneIsUsableOnlyWhenTheGrantedTypeCarriesIt() {
        val grant = grant()
        grant.publish(connected, startedVisible = true)
        assertFalse(grant.microphoneUsable())

        grant.publish(all, startedVisible = true)
        assertTrue(grant.microphoneUsable())
    }

    @Test
    fun aStoppedServiceLendsNoMicrophone() {
        val grant = grant()
        grant.publish(all, startedVisible = true)

        grant.clear()

        assertNull(grant.type.value)
        assertFalse(grant.microphoneUsable())
    }

    @Test
    fun belowAndroid11TheMicrophoneHasNoForegroundRuleSoTheGateStaysOpen() {
        val grant = ForegroundServiceGrant(sdkInt = Build.VERSION_CODES.Q)
        grant.publish(connected, startedVisible = false)

        assertTrue(grant.microphoneUsable())
    }

    /** Android 11 to 13 grant the type without a word and silence the microphone anyway. */
    @Test
    fun onAndroid11To13TheMicrophoneTypeCountsOnlyIfItWasTakenWithTheAppVisible() {
        val grant = ForegroundServiceGrant(sdkInt = Build.VERSION_CODES.TIRAMISU)

        grant.publish(connected or microphone, startedVisible = false)
        assertFalse(grant.microphoneUsable())

        grant.publish(connected or microphone, startedVisible = true)
        assertTrue(grant.microphoneUsable())
    }

    @Test
    fun onAndroid11To13ATypeTakenOutOfSightNeedsPromotionEvenIfNoBitIsMissing() {
        val grant = ForegroundServiceGrant(sdkInt = Build.VERSION_CODES.R)
        grant.publish(connected or microphone, startedVisible = false)

        assertTrue(grant.needsPromotion(wanted = connected or microphone))
    }

    /** On Android 14 the system throws instead, so a published microphone bit is proof enough. */
    @Test
    fun onAndroid14ThePublishedMicrophoneBitIsTrustedWhateverTheVisibility() {
        val grant = grant()
        grant.publish(all, startedVisible = false)

        assertTrue(grant.microphoneUsable())
    }

    @Test
    fun aLiveServiceMissingAWantedTypeNeedsPromotion() {
        val grant = grant()
        grant.publish(connected, startedVisible = false)

        assertTrue(grant.needsPromotion(wanted = all))
    }

    @Test
    fun aServiceThatAlreadyHoldsEveryWantedTypeNeedsNoPromotion() {
        val grant = grant()
        grant.publish(all, startedVisible = true)

        assertFalse(grant.needsPromotion(wanted = all))
        assertFalse(grant.needsPromotion(wanted = connected or microphone))
    }

    @Test
    fun noServiceMeansNoSessionToPromote() {
        assertFalse(grant().needsPromotion(wanted = all))
    }
}
