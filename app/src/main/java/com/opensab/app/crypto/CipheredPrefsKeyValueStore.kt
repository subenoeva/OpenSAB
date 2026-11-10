package com.opensab.app.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.opensab.logging.Log
import com.opensab.sab.session.crypto.KeyValueStore
import java.security.KeyStore

/**
 * [KeyValueStore] over ordinary `SharedPreferences` whose values are sealed by a [KeystoreCipher].
 *
 * This replaces `EncryptedSharedPreferences`, which is deprecated and, worse, fails closed: its
 * Tink keyset lives on disk while the master key that opens it lives in the Keystore, so the two
 * can part ways — a cloud restore, a device transfer, a Keystore that no longer verifies — and
 * every call into the store then throws `AEADBadTagException`, including the one a rider makes by
 * forgetting a motorcycle. Here nothing on disk needs a second half: a value the Keystore key can
 * no longer open reads as absent, and the next connection exchanges fresh keys.
 *
 * Writes use `commit = true` because losing a session key to a process kill means a full secure
 * exchange on the next ride. Keys are stored as written — a MAC address the vehicle store already
 * holds in the clear — and only the AES material is sealed.
 */
class CipheredPrefsKeyValueStore(
    context: Context,
    private val cipher: KeystoreCipher = AndroidKeystoreCipher(),
    fileName: String = DEFAULT_FILE_NAME
) : KeyValueStore {

    private val appContext = context.applicationContext

    private val preferences: SharedPreferences by lazy {
        discardLegacyStore()
        appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    override fun getString(key: String): String? {
        val stored = preferences.getString(key, null) ?: return null
        val encrypted = SessionKeyCodec.decode(stored) ?: return null
        return cipher.decrypt(encrypted)
    }

    override fun putString(key: String, value: String) {
        preferences.edit(commit = true) {
            putString(key, SessionKeyCodec.encode(cipher.encrypt(value)))
        }
    }

    override fun remove(key: String) {
        preferences.edit(commit = true) {
            remove(key)
        }
    }

    override fun keys(): Set<String> = preferences.all.keys.toSet()

    override fun clear() {
        preferences.edit(commit = true) {
            clear()
        }
    }

    /**
     * Drops what `EncryptedSharedPreferences` left behind: the keyset file and the master key alias
     * it generated. Both are dead weight now, and on the phones where the pair had already come
     * apart the file is unreadable anyway. No session key is lost that was still usable.
     */
    private fun discardLegacyStore() {
        if (appContext.deleteSharedPreferences(LEGACY_FILE_NAME)) {
            Log.i("Dropped the legacy encrypted key store; the next connection exchanges fresh keys")
        }
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(LEGACY_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
            }
        }.onFailure { cause ->
            Log.w(cause, "The legacy master key could not be deleted; it is unused either way")
        }
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "opensab_session_keys_v2"
        const val LEGACY_FILE_NAME = "opensab_session_keys"
        const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
