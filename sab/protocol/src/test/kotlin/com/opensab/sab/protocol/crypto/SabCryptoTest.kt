package com.opensab.sab.protocol.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

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

        val encrypted = Cipher.getInstance("AES/ECB/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
            .doFinal(raw16)

        val decrypted = SabCrypto.aesDecryptLenient(key, encrypted)
        assertArrayEquals(raw16, decrypted)
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Golden vector G7: the validation command under the reference key, PKCS#7 padded. */
    @Test
    fun aesEncryptPkcs7GoldenVectorG7ValidationCommand() {
        val key = hex("000102030405060708090A0B0C0D0E0F")
        assertArrayEquals(
            hex("DB4BC948F1988C0B82D74D332F9F2275"),
            SabCrypto.aesEncryptPkcs7(key, hex("034001"))
        )
    }

    /** Golden vector G9, the decrypt half: status 1, incrementId 42 little-endian. */
    @Test
    fun aesDecryptLenientGoldenVectorG9ValidationAnswer() {
        val key = hex("000102030405060708090A0B0C0D0E0F")
        val plain = SabCrypto.aesDecryptLenient(key, hex("2B88B13F99E6FE6F65EE92349CBA7095"))
        assertArrayEquals(hex("012A000000"), plain.copyOfRange(0, 5))
    }
}
