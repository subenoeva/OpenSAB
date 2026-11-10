package com.opensab.sab.session.crypto

/**
 * Minimal string key-value storage contract.
 *
 * It exists to keep [PersistentSecureKeyStore] free of any Android dependency: the stored
 * implementation is a thin adapter over `SharedPreferences` sealed by the Android Keystore, while
 * the key handling logic stays verifiable on the JVM.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
    fun clear()
}
