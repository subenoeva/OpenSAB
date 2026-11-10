package com.opensab.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLogLimitTest {

    @Test
    fun theDefaultIsFiftyMegabytes() = assertEquals(50, AppSettings().sessionLogLimitMb)

    @Test
    fun anUnknownValueReadsAsTheDefault() = assertEquals(50L shl 20, SessionLogLimit.bytes(37))

    @Test
    fun eachOptionIsKept() =
        SessionLogLimit.OPTIONS_MB.forEach { assertEquals(it.toLong() shl 20, SessionLogLimit.bytes(it)) }
}
