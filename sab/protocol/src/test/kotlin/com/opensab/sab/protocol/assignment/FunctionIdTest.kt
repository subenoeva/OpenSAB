package com.opensab.sab.protocol.assignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FunctionIdTest {

    @Test
    fun fromValueMappingsFor1To10() {
        assertEquals(FunctionId.PREVIOUS, FunctionId.fromValue(1))
        assertEquals(FunctionId.NEXT, FunctionId.fromValue(2))
        assertEquals(FunctionId.BACK, FunctionId.fromValue(3))
        assertEquals(FunctionId.SELECT, FunctionId.fromValue(4))
        assertEquals(FunctionId.VOLUME_UP, FunctionId.fromValue(5))
        assertEquals(FunctionId.VOLUME_DOWN, FunctionId.fromValue(6))
        assertEquals(FunctionId.VOLUME_MUTE, FunctionId.fromValue(7))
        assertEquals(FunctionId.MENU, FunctionId.fromValue(8))
        assertEquals(FunctionId.MODE_SAB, FunctionId.fromValue(9))
        assertEquals(FunctionId.MODE_HU, FunctionId.fromValue(10))
    }

    @Test
    fun fromValueOutOfBoundsReturnsNull() {
        assertNull(FunctionId.fromValue(0))
        assertNull(FunctionId.fromValue(11))
        assertNull(FunctionId.fromValue(255))
    }
}
