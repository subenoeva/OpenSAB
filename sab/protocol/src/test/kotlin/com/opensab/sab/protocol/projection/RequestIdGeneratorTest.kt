package com.opensab.sab.protocol.projection

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestIdGeneratorTest {

    @Test
    fun generatesMonotonicIncrementingIds() {
        val generator = RequestIdGenerator()
        val id1 = generator.nextId()
        val id2 = generator.nextId()
        val id3 = generator.nextId()

        assertEquals(1, id1)
        assertEquals(2, id2)
        assertEquals(3, id3)
    }

    @Test
    fun wrapsAroundAtMaxByteValue() {
        val generator = RequestIdGenerator(initialValue = 254)
        assertEquals(254, generator.nextId())
        assertEquals(255, generator.nextId())
        assertEquals(1, generator.nextId()) // Wraps to 1
    }
}
