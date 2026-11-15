package com.opensab.sab.protocol.projection

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveIndicatorTest {

    @Test
    fun eachIndicatorCarriesItsBitOfTheStatusBarMask() {
        assertEquals(
            mapOf(
                ActiveIndicator.PHONE to 0x01,
                ActiveIndicator.NAVIGATION to 0x02,
                ActiveIndicator.MUSIC to 0x04,
                ActiveIndicator.MESSAGE to 0x08,
                ActiveIndicator.MUTE to 0x10,
                ActiveIndicator.NO_NETWORK to 0x20
            ),
            ActiveIndicator.entries.associateWith { it.bit }
        )
    }

    @Test
    fun maskOfGoldenVectorG2NavigationAndMusic() {
        assertEquals(0x06, ActiveIndicator.maskOf(listOf(ActiveIndicator.NAVIGATION, ActiveIndicator.MUSIC)))
    }

    @Test
    fun maskOfCombinesPhoneAndNavigationWithOr() {
        assertEquals(0x03, ActiveIndicator.maskOf(setOf(ActiveIndicator.PHONE, ActiveIndicator.NAVIGATION)))
    }

    @Test
    fun maskOfNothingLitIsZero() {
        assertEquals(0, ActiveIndicator.maskOf(emptyList()))
    }

    @Test
    fun maskOfEveryIndicatorFillsTheSixLowBits() {
        assertEquals(0x3F, ActiveIndicator.maskOf(ActiveIndicator.entries))
    }
}
