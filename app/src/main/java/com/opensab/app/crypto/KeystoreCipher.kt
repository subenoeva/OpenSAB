package com.opensab.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals short strings with an AES-256-GCM key that never leaves the Android Keystore.
 *
 * The key is the whole scheme: no keyset file, no wrapped key material on disk, nothing to restore
 * out of step with the Keystore. What is stored is only ciphertext and its IV, and a ciphertext the
 * key can no longer open reads back as absent — which for a session key means the next connection
 * runs a fresh secure exchange, the same answer the codec already gives for a corrupt record.
 */
interface KeystoreCipher {

    fun encrypt(plaintext: String): EncryptedData

    /** Null when the ciphertext cannot be opened: a lost key must never be a thrown key. */
    fun decrypt(encrypted: EncryptedData): String?

    data class EncryptedData(val ciphertext: ByteArray, val iv: ByteArray) {
        // ByteArray equality is identity, which would make two equal records compare different.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EncryptedData

            if (!ciphertext.contentEquals(other.ciphertext)) return false
            if (!iv.contentEquals(other.iv)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = ciphertext.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            return result
        }
    }
}

/**
 * [KeystoreCipher] over the platform `AndroidKeyStore` provider.
 *
 * The IV is the one the provider generates per operation and is stored beside the ciphertext:
 * GCM repeating an IV under the same key is what breaks the cipher, so it is never supplied here.
 */
class AndroidKeystoreCipher(
    private val alias: String = DEFAULT_ALIAS
) : KeystoreCipher {

    override fun encrypt(plaintext: String): KeystoreCipher.EncryptedData {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        return KeystoreCipher.EncryptedData(
            ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)),
            iv = cipher.iv
        )
    }

    override fun decrypt(encrypted: KeystoreCipher.EncryptedData): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, encrypted.iv)
        )
        String(cipher.doFinal(encrypted.ciphertext), Charsets.UTF_8)
    } catch (_: GeneralSecurityException) {
        // Covers the tag check failing because the key that sealed this is gone.
        null
    } catch (_: IOException) {
        null
    } catch (_: ProviderException) {
        // The Keystore daemon reports its own faults as unchecked, and they are not ours to throw.
        null
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // The rider is riding: a key that waits for the lock screen would strand a session.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val DEFAULT_ALIAS = "opensab_session_key_wrapper"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION =
            "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"
        const val GCM_TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
