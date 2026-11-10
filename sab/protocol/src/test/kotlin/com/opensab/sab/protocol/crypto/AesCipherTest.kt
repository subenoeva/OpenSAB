package com.opensab.sab.protocol.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AesCipherTest {

    @Test
    fun encryptAndDecryptEcbShouldBeSymmetric() {
        val key = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        val plaintext = "OpenSABSecretMsg".toByteArray(Charsets.US_ASCII) // exactly 16 bytes

        val ciphertext = AesCipher.encryptEcb(key, plaintext)
        assertEquals(16, ciphertext.size)
        assertFalse(plaintext.contentEquals(ciphertext))

        val decrypted = AesCipher.decryptEcb(key, ciphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encryptWithInvalidKeyLengthShouldThrow() {
        val invalidKey = ByteArray(8)
        val plaintext = ByteArray(16)
        AesCipher.encryptEcb(invalidKey, plaintext)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encryptWithNonBlockAlignedPlaintextShouldThrow() {
        val key = ByteArray(16)
        val unalignedPlaintext = ByteArray(15) // Not a multiple of 16
        AesCipher.encryptEcb(key, unalignedPlaintext)
    }
}
