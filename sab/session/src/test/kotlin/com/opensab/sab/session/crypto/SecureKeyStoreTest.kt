package com.opensab.sab.session.crypto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureKeyStoreTest {

    @Test
    fun saveGetAndRemoveSessionKey() = runBlocking {
        val store: SecureKeyStore = InMemorySecureKeyStore()
        val mac = "AA:BB:CC:DD:EE:FF"
        val key = ByteArray(16) { 0x55.toByte() }

        assertFalse(store.hasSessionKey(mac))
        assertNull(store.getSessionKey(mac))

        store.saveSessionKey(mac, key)
        assertTrue(store.hasSessionKey(mac))
        assertArrayEquals(key, store.getSessionKey(mac))

        store.removeSessionKey(mac)
        assertFalse(store.hasSessionKey(mac))
        assertNull(store.getSessionKey(mac))
    }
}
