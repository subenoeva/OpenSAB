package com.opensab.sab.session.crypto

import com.opensab.logging.Log
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * [SecureKeyStore] persisting AES-128 session keys through a [KeyValueStore].
 *
 * In production the backing store seals every value with a key held in the Android Keystore, so
 * the key material never touches the filesystem in the clear. Anything that does not decode back
 * to exactly 16 bytes is treated as absent: a corrupt entry must force a fresh secure exchange,
 * never a broken session.
 */
class PersistentSecureKeyStore(
    private val store: KeyValueStore
) : SecureKeyStore {

    override suspend fun saveSessionKey(macAddress: String, key: ByteArray) {
        require(key.size == AES_KEY_SIZE) { "Invalid key size: expected $AES_KEY_SIZE bytes, got ${key.size}" }
        withContext(Dispatchers.IO) {
            store.putString(keyFor(macAddress), Base64.getEncoder().encodeToString(key))
        }
    }

    override suspend fun getSessionKey(macAddress: String): ByteArray? = withContext(Dispatchers.IO) {
        val encoded = store.getString(keyFor(macAddress)) ?: return@withContext null
        val decoded = runCatchingCancellable { Base64.getDecoder().decode(encoded) }.getOrElse { cause ->
            Log.w(cause, "Stored session key for $macAddress is not decodable; forcing a new handshake")
            return@withContext null
        }
        if (decoded.size != AES_KEY_SIZE) {
            Log.w("Stored session key for $macAddress has ${decoded.size} bytes; forcing a new handshake")
            return@withContext null
        }
        decoded
    }

    override suspend fun hasSessionKey(macAddress: String): Boolean = getSessionKey(macAddress) != null

    override suspend fun removeSessionKey(macAddress: String) = withContext(Dispatchers.IO) {
        store.remove(keyFor(macAddress))
    }

    /** Drops every session key without touching unrelated entries of the same backing file. */
    override suspend fun clear() = withContext(Dispatchers.IO) {
        store.keys().filter { it.startsWith(KEY_PREFIX) }.forEach { store.remove(it) }
    }

    private fun keyFor(macAddress: String) = "$KEY_PREFIX$macAddress"

    private companion object {
        const val KEY_PREFIX = "session_key_"
        const val AES_KEY_SIZE = 16
    }
}
