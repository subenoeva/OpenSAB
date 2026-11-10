package com.opensab.app.crypto

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Under the conditions that broke `EncryptedSharedPreferences`: the Keystore no longer verified the
 * Tink keyset on disk and `AEADBadTagException` came out of every call. The same loss must now read
 * as an absent key (the next connection exchanges fresh ones), and nothing may throw.
 */
@RunWith(AndroidJUnit4::class)
class CipheredPrefsKeyValueStoreTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val cipher = AndroidKeystoreCipher(TEST_ALIAS)

    @After
    fun tearDown() {
        context.deleteSharedPreferences(FILE_NAME)
        deleteTestKey()
    }

    @Test
    fun savesAndReadsBackAValue() {
        val store = newStore()

        store.putString(KEY, VALUE)

        assertEquals(VALUE, store.getString(KEY))
        assertEquals(setOf(KEY), store.keys())
    }

    @Test
    fun theStoredBytesAreNotThePlaintext() {
        newStore().putString(KEY, VALUE)

        val stored = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getString(KEY, null)

        assertFalse("The AES material must not be readable off the file", stored!!.contains(VALUE))
    }

    @Test
    fun aValueTheKeystoreKeyCanNoLongerOpenReadsAsAbsent() {
        newStore().putString(KEY, VALUE)

        deleteTestKey()

        val reopened = newStore()
        assertNull(
            "A value sealed with a key that is gone is unreadable, so it must read as absent",
            reopened.getString(KEY)
        )
        // The call that killed the app when the rider forgot a motorcycle.
        reopened.remove(KEY)

        // And the store has to be usable again, or the next handshake would have nowhere to write.
        reopened.putString(KEY, VALUE)
        assertEquals(VALUE, reopened.getString(KEY))
    }

    @Test
    fun aValueWrittenBySomethingElseReadsAsAbsent() {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putString(KEY, "not an IV and a ciphertext")
        }

        assertNull(newStore().getString(KEY))
    }

    @Test
    fun theLegacyEncryptedStoreIsDropped() {
        context.getSharedPreferences(LEGACY_FILE_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putString("session_key_AA:BB:CC:DD:EE:FF", "whatever EncryptedSharedPreferences wrote")
        }
        val legacyFile = File(File(context.applicationInfo.dataDir, "shared_prefs"), "$LEGACY_FILE_NAME.xml")
        assertTrue("The legacy file has to exist for this case to mean anything", legacyFile.exists())

        // The store opens lazily, so it takes a read to reach the cleanup.
        newStore().getString(KEY)

        assertFalse(legacyFile.exists())
    }

    private fun newStore() = CipheredPrefsKeyValueStore(context, cipher, FILE_NAME)

    private fun deleteTestKey() {
        KeyStore.getInstance(ANDROID_KEYSTORE)
            .apply { load(null) }
            .deleteEntry(TEST_ALIAS)
    }

    private companion object {
        const val FILE_NAME = "opensab_test_session_keys"
        const val LEGACY_FILE_NAME = "opensab_session_keys"
        const val TEST_ALIAS = "opensab_test_session_key_wrapper"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY = "session_key_AA:BB:CC:DD:EE:FF"
        const val VALUE = "3q2+7w4rTBiFvGKM1jgUxQ=="
    }
}
