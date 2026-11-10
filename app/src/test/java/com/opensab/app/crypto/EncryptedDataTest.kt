package com.opensab.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EncryptedDataTest {

    @Test
    fun equalRecordsInDistinctArraysAreEqualAndHashTheSame() {
        val a = KeystoreCipher.EncryptedData(byteArrayOf(1, 2, 3), byteArrayOf(9, 8))
        val b = KeystoreCipher.EncryptedData(byteArrayOf(1, 2, 3), byteArrayOf(9, 8))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun recordsThatDifferInEitherArrayAreNotEqual() {
        val base = KeystoreCipher.EncryptedData(byteArrayOf(1, 2, 3), byteArrayOf(9, 8))

        assertNotEquals(base, KeystoreCipher.EncryptedData(byteArrayOf(1, 2, 4), byteArrayOf(9, 8)))
        assertNotEquals(base, KeystoreCipher.EncryptedData(byteArrayOf(1, 2, 3), byteArrayOf(9, 7)))
    }
}
