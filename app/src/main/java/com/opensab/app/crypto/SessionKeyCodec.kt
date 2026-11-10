package com.opensab.app.crypto

import com.opensab.logging.Log
import java.util.Base64

/**
 * The stored form of a sealed session key: the IV and the ciphertext, each in Base64, joined by
 * [SEPARATOR]. Keys written by earlier versions are read with it, so the format is frozen.
 */
object SessionKeyCodec {

    private const val SEPARATOR = ":"

    fun encode(encrypted: KeystoreCipher.EncryptedData): String {
        val encoder = Base64.getEncoder()
        return "${encoder.encodeToString(encrypted.iv)}$SEPARATOR${encoder.encodeToString(encrypted.ciphertext)}"
    }

    /** Null for anything this codec did not write, which the store treats as a missing entry. */
    fun decode(stored: String): KeystoreCipher.EncryptedData? {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) {
            Log.w("Discarding a stored value that is not an IV and a ciphertext")
            return null
        }
        val decoder = Base64.getDecoder()
        val encrypted = try {
            KeystoreCipher.EncryptedData(
                ciphertext = decoder.decode(parts[1]),
                iv = decoder.decode(parts[0])
            )
        } catch (cause: IllegalArgumentException) {
            Log.w(cause, "Discarding a stored value that is not decodable")
            return null
        }
        // Every IV this codec wrote came from the cipher, and GCM never hands out an empty one.
        if (encrypted.iv.isEmpty()) {
            Log.w("Discarding a stored value without an IV")
            return null
        }
        return encrypted
    }
}
