package com.opensab.sab.session.crypto

import java.util.concurrent.ConcurrentHashMap

class InMemorySecureKeyStore : SecureKeyStore {

    private val store = ConcurrentHashMap<String, ByteArray>()

    override suspend fun saveSessionKey(macAddress: String, key: ByteArray) {
        store[macAddress] = key.clone()
    }

    override suspend fun getSessionKey(macAddress: String): ByteArray? {
        return store[macAddress]?.clone()
    }

    override suspend fun hasSessionKey(macAddress: String): Boolean {
        return store.containsKey(macAddress)
    }

    override suspend fun removeSessionKey(macAddress: String) {
        store.remove(macAddress)
    }

    override suspend fun clear() {
        store.clear()
    }
}
