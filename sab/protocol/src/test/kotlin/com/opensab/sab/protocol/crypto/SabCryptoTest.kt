package com.opensab.sab.protocol.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.RSAPublicKey

class SabCryptoTest {

    @Test
    fun generateRsaKeyPairShouldReturn1024BitKeyWithF4Exponent() {
        val keyPair = SabCrypto.generateRsaKeyPair(1024)
        val pubKey = keyPair.public as RSAPublicKey
        assertEquals(1024, pubKey.modulus.bitLength())
        assertEquals(65537.toBigInteger(), pubKey.publicExponent)
    }

    @Test
    fun extractModulusShouldReturnExact128Bytes() {
        val keyPair = SabCrypto.generateRsaKeyPair(1024)
        val pubKey = keyPair.public as RSAPublicKey
        val modulus = SabCrypto.extractModulus(pubKey)
        assertEquals(128, modulus.size)
        // High bit must be set in 1024-bit modulus
        assertTrue((modulus[0].toInt() and 0x80) != 0)
    }

    @Test
    fun rsaEncryptAndDecryptRoundtrip() {
        val keyPair = SabCrypto.generateRsaKeyPair(1024)
        val plain = ByteArray(128) { (it + 5).toByte() }
        plain[0] = 0x00 // Ensure plaintext < modulus for textbook RSA

        val ciphertext = SabCrypto.rsaEncrypt(keyPair.public, plain)
        assertEquals(128, ciphertext.size)

        val decrypted = SabCrypto.rsaDecrypt(keyPair.private, ciphertext)
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun aesEncryptPkcs7AndDecryptLenientRoundtrip() {
        val key = ByteArray(16) { 0x55.toByte() }
        val plain = byteArrayOf(0x03, 0x40, 0x01)

        val encrypted = SabCrypto.aesEncryptPkcs7(key, plain)
        assertEquals(16, encrypted.size)

        val decrypted = SabCrypto.aesDecryptLenient(key, encrypted)
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun aesDecryptLenientFallbackToNoPaddingWhenPaddingCorrupt() {
        val key = ByteArray(16) { 0x12.toByte() }
        val raw16 = ByteArray(16) { (it * 3).toByte() }

        val encrypted = AesCipher.encryptEcb(key, raw16)

        val decrypted = SabCrypto.aesDecryptLenient(key, encrypted)
        assertArrayEquals(raw16, decrypted)
    }
}
