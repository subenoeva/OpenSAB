package com.opensab.sab.protocol.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** ECB is not a choice: the BTU security handshake mandates AES-128 in ECB mode. */
internal object AesCipher {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/ECB/NoPadding"
    private const val BLOCK_SIZE = 16

    fun encryptEcb(key: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == BLOCK_SIZE) { "AES-128 key must be $BLOCK_SIZE bytes, got ${key.size}" }
        require(plaintext.size % BLOCK_SIZE == 0 && plaintext.isNotEmpty()) {
            "Plaintext length (${plaintext.size}) must be a non-empty multiple of $BLOCK_SIZE bytes for ECB mode"
        }

        val secretKey = SecretKeySpec(key, ALGORITHM)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.doFinal(plaintext)
    }

    fun decryptEcb(key: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == BLOCK_SIZE) { "AES-128 key must be $BLOCK_SIZE bytes, got ${key.size}" }
        require(ciphertext.size % BLOCK_SIZE == 0 && ciphertext.isNotEmpty()) {
            "Ciphertext length (${ciphertext.size}) must be a non-empty multiple of $BLOCK_SIZE bytes for ECB mode"
        }

        val secretKey = SecretKeySpec(key, ALGORITHM)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        return cipher.doFinal(ciphertext)
    }
}
