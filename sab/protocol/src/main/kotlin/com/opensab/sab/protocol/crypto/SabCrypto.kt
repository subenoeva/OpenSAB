package com.opensab.sab.protocol.crypto

import java.security.Key
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives of the BTU session handshake. ECB is not a choice: the BTU mandates AES-128 in ECB
 * mode with PKCS5/PKCS7 padding for the initial authentication frames.
 */
object SabCrypto {

    const val RSA_KEY_SIZE = 1024

    /** The exponent must be F4 (65537): the BTU assumes it implicitly. */
    fun generateRsaKeyPair(keySize: Int = RSA_KEY_SIZE): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(keySize, RSAKeyGenParameterSpec.F4))
        return generator.generateKeyPair()
    }

    /**
     * Extracts the 128-byte big-endian RSA modulus from a public key, stripping any leading sign byte (0x00).
     */
    fun extractModulus(publicKey: RSAPublicKey): ByteArray {
        val raw = publicKey.modulus.toByteArray()
        val stripped = if (raw.isNotEmpty() && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
        require(stripped.size == 128) {
            "Expected 128-byte RSA modulus, got ${stripped.size}"
        }
        return stripped
    }

    fun rsaEncrypt(key: Key, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(plaintext)
    }

    fun rsaDecrypt(key: Key, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher.doFinal(ciphertext)
    }

    fun aesEncryptPkcs7(key: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 16) { "AES key must be 16 bytes, got ${key.size}" }
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.doFinal(plaintext)
    }

    /** Tries PKCS7/PKCS5 padding first and falls back to NoPadding. */
    fun aesDecryptLenient(key: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == 16) { "AES key must be 16 bytes, got ${key.size}" }
        val secretKey = SecretKeySpec(key, "AES")
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            cipher.doFinal(ciphertext)
        } catch (_: Throwable) {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            cipher.doFinal(ciphertext)
        }
    }
}
