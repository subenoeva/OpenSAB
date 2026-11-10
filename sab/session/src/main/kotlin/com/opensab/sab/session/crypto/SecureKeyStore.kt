package com.opensab.sab.session.crypto

interface SecureKeyStore {
    suspend fun saveSessionKey(macAddress: String, key: ByteArray)
    suspend fun getSessionKey(macAddress: String): ByteArray?
    suspend fun hasSessionKey(macAddress: String): Boolean
    suspend fun removeSessionKey(macAddress: String)
    suspend fun clear()
}
