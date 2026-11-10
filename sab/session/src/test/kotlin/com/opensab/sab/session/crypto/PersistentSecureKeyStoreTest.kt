package com.opensab.sab.session.crypto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentSecureKeyStoreTest {

    /** Stands in for the prefs-backed store, whose sealing needs the Android Keystore. */
    private class FakeKeyValueStore : KeyValueStore {
        val entries = linkedMapOf<String, String>()
        override fun getString(key: String): String? = entries[key]
        override fun putString(key: String, value: String) {
            entries[key] = value
        }

        override fun remove(key: String) {
            entries.remove(key)
        }

        override fun keys(): Set<String> = entries.keys.toSet()
        override fun clear() = entries.clear()
    }

    private val mac = "AA:BB:CC:DD:EE:FF"
    private val key = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun savesAndReadsBackSessionKey() = runBlocking {
        val backing = FakeKeyValueStore()
        val store: SecureKeyStore = PersistentSecureKeyStore(backing)

        assertFalse(store.hasSessionKey(mac))
        assertNull(store.getSessionKey(mac))

        store.saveSessionKey(mac, key)

        assertTrue(store.hasSessionKey(mac))
        assertArrayEquals(key, store.getSessionKey(mac))
    }

    @Test
    fun keySurvivesANewStoreInstanceOverTheSameBacking() = runBlocking {
        val backing = FakeKeyValueStore()
        PersistentSecureKeyStore(backing).saveSessionKey(mac, key)

        // Simulates a process restart: the encrypted file stays, the object graph is rebuilt.
        val reopened: SecureKeyStore = PersistentSecureKeyStore(backing)

        assertTrue(reopened.hasSessionKey(mac))
        assertArrayEquals(key, reopened.getSessionKey(mac))
    }

    @Test
    fun neverStoresTheKeyAsRawText() = runBlocking {
        val backing = FakeKeyValueStore()
        PersistentSecureKeyStore(backing).saveSessionKey(mac, key)

        val stored = backing.entries.values.single()
        assertFalse("The raw key bytes must not be readable in the stored value", stored.toByteArray(Charsets.ISO_8859_1).contentEquals(key))
    }

    @Test
    fun returnsDefensiveCopies() = runBlocking {
        val backing = FakeKeyValueStore()
        val store: SecureKeyStore = PersistentSecureKeyStore(backing)

        val source = key.copyOf()
        store.saveSessionKey(mac, source)
        source.fill(0x00)

        val readBack = store.getSessionKey(mac)
        assertArrayEquals(key, readBack)

        readBack!!.fill(0x7F)
        assertArrayEquals(key, store.getSessionKey(mac))
    }

    @Test
    fun rejectsStoredValueWithWrongLength() = runBlocking {
        val backing = FakeKeyValueStore()
        val store: SecureKeyStore = PersistentSecureKeyStore(backing)
        store.saveSessionKey(mac, key)

        val storedKey = backing.entries.keys.single()
        backing.entries[storedKey] = "AAAA"  // decodes to 3 bytes, not a valid AES-128 key

        assertNull(store.getSessionKey(mac))
        assertFalse(store.hasSessionKey(mac))
    }

    @Test
    fun rejectsStoredValueThatIsNotDecodable() = runBlocking {
        val backing = FakeKeyValueStore()
        val store: SecureKeyStore = PersistentSecureKeyStore(backing)
        store.saveSessionKey(mac, key)

        val storedKey = backing.entries.keys.single()
        backing.entries[storedKey] = "!!! not base64 !!!"

        assertNull(store.getSessionKey(mac))
        assertFalse(store.hasSessionKey(mac))
    }

    @Test
    fun removeAndClearOnlyTouchOwnKeys() = runBlocking {
        val backing = FakeKeyValueStore()
        backing.putString("unrelated_setting", "keep me")
        val store: SecureKeyStore = PersistentSecureKeyStore(backing)

        store.saveSessionKey(mac, key)
        store.saveSessionKey("11:22:33:44:55:66", ByteArray(16) { 0x5A })

        store.removeSessionKey(mac)
        assertFalse(store.hasSessionKey(mac))
        assertTrue(store.hasSessionKey("11:22:33:44:55:66"))

        store.clear()
        assertFalse(store.hasSessionKey("11:22:33:44:55:66"))
        assertEquals("keep me", backing.getString("unrelated_setting"))
    }
}
