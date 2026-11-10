package com.opensab.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionKeyCodecTest {

    private val iv = ByteArray(12) { it.toByte() }
    private val ciphertext = "session-key".toByteArray()
    private val stored = "AAECAwQFBgcICQoL:c2Vzc2lvbi1rZXk="

    @Test
    fun encodeWritesTheIvAndTheCiphertextAsBase64AroundTheSeparator() {
        assertEquals(stored, SessionKeyCodec.encode(KeystoreCipher.EncryptedData(ciphertext, iv)))
    }

    @Test
    fun decodeReadsAValueTheCurrentVersionWrote() {
        assertEquals(KeystoreCipher.EncryptedData(ciphertext, iv), SessionKeyCodec.decode(stored))
    }

    @Test
    fun encodeThenDecodeGivesBackTheSameRecord() {
        val record = KeystoreCipher.EncryptedData(ByteArray(48) { (it * 7).toByte() }, ByteArray(12) { (-it).toByte() })

        assertEquals(record, SessionKeyCodec.decode(SessionKeyCodec.encode(record)))
    }

    @Test
    fun decodeDiscardsASinglePart() {
        assertNull(SessionKeyCodec.decode("AAECAwQFBgcICQoL"))
    }

    @Test
    fun decodeDiscardsThreeParts() {
        assertNull(SessionKeyCodec.decode("$stored:AAAA"))
    }

    @Test
    fun decodeDiscardsInvalidBase64() {
        assertNull(SessionKeyCodec.decode("AAECAwQF*GcICQoL:c2Vzc2lvbi1rZXk="))
    }

    @Test
    fun decodeDiscardsAnEmptyString() {
        assertNull(SessionKeyCodec.decode(""))
    }

    @Test
    fun decodeDiscardsAnEmptyIv() {
        assertNull(SessionKeyCodec.decode(":c2Vzc2lvbi1rZXk="))
    }
}
