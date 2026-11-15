package com.opensab.sab.session.crypto

import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.crypto.ChecksumMismatchException
import com.opensab.sab.protocol.crypto.Crc16Arc
import com.opensab.sab.protocol.crypto.SabCrypto
import com.opensab.sab.session.ble.MockGattTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec

class SecuritySessionManagerTest {

    @Test
    fun establishSessionShouldReuseKeyAndValidateWhenAlreadySecured() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()

        val mac = "11:22:33:44:55:66"
        val existingKey = ByteArray(16) { 0x77.toByte() }

        // Set SecurityStatus to 0x01 (Already Secured)
        transport.setCharacteristicValue(
            SabGattUuids.CHAR_SECURITY_STATUS,
            byteArrayOf(0x01)
        )

        // Setup validation response on DataSource when ControlPoint is written
        transport.onCharacteristicWrite = { charUuid, payload ->
            if (charUuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT && payload.size == 18) {
                // Validation response: [0x01, 0x00] + aesEncrypt([status=1, incrementId=16])
                val valPayload = byteArrayOf(0x01, 0x10, 0x00, 0x00, 0x00)
                val encVal = SabCrypto.aesEncryptPkcs7(existingKey, valPayload)
                val resp = byteArrayOf(0x01, 0x00) + encVal
                transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, resp)
            }
        }

        val keyStore = InMemorySecureKeyStore()
        keyStore.saveSessionKey(mac, existingKey)

        val sessionManager = DefaultSecuritySessionManager(keyStore)
        val result = sessionManager.establishSession(mac, transport)

        assertTrue(result.isSuccess)
        val session = result.getOrNull()!!
        assertEquals(mac, session.macAddress)
        assertArrayEquals(existingKey, session.aesKey)
        assertTrue(session.isValidated)
        assertEquals(16, session.incrementId)
    }

    @Test
    fun establishSessionShouldFailWhenAlreadySecuredButNoStoredKey() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()

        transport.setCharacteristicValue(
            SabGattUuids.CHAR_SECURITY_STATUS,
            byteArrayOf(0x01)
        )

        val keyStore = InMemorySecureKeyStore()
        val mac = "11:22:33:44:55:66"

        val sessionManager = DefaultSecuritySessionManager(keyStore)
        val result = sessionManager.establishSession(mac, transport)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoStoredKeyException)
    }

    @Test
    fun establishSessionFullHandshakeSuccess() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()

        // Set SecurityStatus to 0x00 (Requires Handshake)
        transport.setCharacteristicValue(
            SabGattUuids.CHAR_SECURITY_STATUS,
            byteArrayOf(0x00)
        )

        val btuAesKey = ByteArray(16) { (it + 1).toByte() }

        // Setup BTU mock behavior on ControlPoint write
        transport.onCharacteristicWrite = { charUuid, payload ->
            if (charUuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT) {
                if (payload.size == 129 && payload[0] == 0x02.toByte()) {
                    // SECURE_EXCHANGE: extract modulus, encrypt 128-byte block
                    val modulusBytes = payload.copyOfRange(1, 129)
                    val spec = RSAPublicKeySpec(BigInteger(1, modulusBytes), 65537.toBigInteger())
                    val pubKey = KeyFactory.getInstance("RSA").generatePublic(spec)

                    // 110 padding + 16 AES key + 2 CRC = 128 bytes
                    val padding = ByteArray(110) { 0x00.toByte() }
                    val plainBlock = padding + btuAesKey + Crc16Arc.calculateLe(btuAesKey)
                    val encryptedBlock = SabCrypto.rsaEncrypt(pubKey, plainBlock)
                    transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, encryptedBlock)
                } else if (payload.size == 18) {
                    // VALIDATION: respond with status 1 and incrementId 42
                    val valPayload = byteArrayOf(0x01, 0x2A, 0x00, 0x00, 0x00)
                    val encVal = SabCrypto.aesEncryptPkcs7(btuAesKey, valPayload)
                    val resp = byteArrayOf(0x01, 0x00) + encVal
                    transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, resp)
                }
            }
        }

        val keyStore = InMemorySecureKeyStore()
        val mac = "AA:BB:CC:11:22:33"

        val sessionManager = DefaultSecuritySessionManager(keyStore)
        val result = sessionManager.establishSession(mac, transport)

        assertTrue("Expected handshake success", result.isSuccess)
        val session = result.getOrNull()!!
        assertEquals(mac, session.macAddress)
        assertArrayEquals(btuAesKey, session.aesKey)
        assertTrue(session.isValidated)
        assertEquals(42, session.incrementId)
        assertTrue(keyStore.hasSessionKey(mac))
    }

    /** The comparison is of the whole array, so `[0x01, 0x00]` is not "secured". */
    @Test
    fun aSecurityStatusWithATrailingByteIsNotAlreadySecured() = runBlocking {
        val transport = handshakingBtu(btuAesKey = ByteArray(16) { (it + 1).toByte() })
        transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x01, 0x00))
        val mac = "AA:BB:CC:11:22:33"
        val keyStore = InMemorySecureKeyStore().apply { saveSessionKey(mac, ByteArray(16) { 0x77 }) }

        val session = DefaultSecuritySessionManager(keyStore).establishSession(mac, transport).getOrThrow()

        assertArrayEquals("the key comes from a fresh exchange", ByteArray(16) { (it + 1).toByte() }, session.aesKey)
    }

    /**
     * The BTU may not have the RSA answer ready the instant it is read. A short read is retried
     * before the exchange is given up.
     */
    @Test
    fun anEarlyShortSecureExchangeReadIsRetried() = runBlocking {
        val btuAesKey = ByteArray(16) { (it + 1).toByte() }
        val transport = handshakingBtu(btuAesKey)
        var dataSourceReads = 0
        var answer: ByteArray? = null
        val btu = transport.onCharacteristicWrite
        transport.onCharacteristicWrite = { uuid, payload ->
            btu?.invoke(uuid, payload)
            if (uuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT && payload.size == 129) {
                answer = transport.readStoredDataSource()
                transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, ByteArray(20))
            }
        }
        transport.onCharacteristicRead = { uuid ->
            if (uuid == SabGattUuids.CHAR_SECURITY_DATA_SOURCE && answer != null && ++dataSourceReads == 2) {
                transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, answer!!)
                answer = null
            }
        }

        val session = DefaultSecuritySessionManager(InMemorySecureKeyStore())
            .establishSession("AA:BB:CC:11:22:33", transport).getOrThrow()

        assertArrayEquals(btuAesKey, session.aesKey)
        assertEquals(2, dataSourceReads)
    }

    @Test
    fun establishSessionShouldFailWhenCrcMismatch() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()

        transport.setCharacteristicValue(
            SabGattUuids.CHAR_SECURITY_STATUS,
            byteArrayOf(0x00)
        )

        val btuAesKey = ByteArray(16) { 0x33.toByte() }

        transport.onCharacteristicWrite = { charUuid, payload ->
            if (charUuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT && payload.size == 129) {
                val modulusBytes = payload.copyOfRange(1, 129)
                val spec = RSAPublicKeySpec(BigInteger(1, modulusBytes), 65537.toBigInteger())
                val pubKey = KeyFactory.getInstance("RSA").generatePublic(spec)

                // Corrupt CRC
                val padding = ByteArray(110) { 0x00.toByte() }
                val corruptCrc = byteArrayOf(0x00, 0x00)
                val plainBlock = padding + btuAesKey + corruptCrc
                val encryptedBlock = SabCrypto.rsaEncrypt(pubKey, plainBlock)
                transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, encryptedBlock)
            }
        }

        val keyStore = InMemorySecureKeyStore()
        val mac = "AA:BB:CC:11:22:33"

        val sessionManager = DefaultSecuritySessionManager(keyStore)
        val result = sessionManager.establishSession(mac, transport)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ChecksumMismatchException)
    }

    @Test
    fun establishSessionShouldFailWhenValidationRejectsKey() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()

        transport.setCharacteristicValue(
            SabGattUuids.CHAR_SECURITY_STATUS,
            byteArrayOf(0x00)
        )

        val btuAesKey = ByteArray(16) { 0x44.toByte() }

        transport.onCharacteristicWrite = { charUuid, payload ->
            if (charUuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT) {
                if (payload.size == 129) {
                    val modulusBytes = payload.copyOfRange(1, 129)
                    val spec = RSAPublicKeySpec(BigInteger(1, modulusBytes), 65537.toBigInteger())
                    val pubKey = KeyFactory.getInstance("RSA").generatePublic(spec)

                    val padding = ByteArray(110) { 0x00.toByte() }
                    val plainBlock = padding + btuAesKey + Crc16Arc.calculateLe(btuAesKey)
                    val encryptedBlock = SabCrypto.rsaEncrypt(pubKey, plainBlock)
                    transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, encryptedBlock)
                } else if (payload.size == 18) {
                    // Validation failure: status = 0x02
                    val valPayload = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00)
                    val encVal = SabCrypto.aesEncryptPkcs7(btuAesKey, valPayload)
                    val resp = byteArrayOf(0x01, 0x00) + encVal
                    transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, resp)
                }
            }
        }

        val keyStore = InMemorySecureKeyStore()
        val mac = "AA:BB:CC:11:22:33"

        val sessionManager = DefaultSecuritySessionManager(keyStore)
        val result = sessionManager.establishSession(mac, transport)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is KeyValidationException)
    }

    /** A BTU that answers the secure exchange with [btuAesKey] and validates it with incrementId 42. */
    private fun handshakingBtu(btuAesKey: ByteArray) = MockGattTransport().apply {
        connect()
        setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x00))
        onCharacteristicWrite = { charUuid, payload ->
            if (charUuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT) {
                if (payload.size == 129 && payload[0] == 0x02.toByte()) {
                    val spec = RSAPublicKeySpec(BigInteger(1, payload.copyOfRange(1, 129)), 65537.toBigInteger())
                    val pubKey = KeyFactory.getInstance("RSA").generatePublic(spec)
                    val plainBlock = ByteArray(110) + btuAesKey + Crc16Arc.calculateLe(btuAesKey)
                    setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, SabCrypto.rsaEncrypt(pubKey, plainBlock))
                } else if (payload.size == 18) {
                    val enc = SabCrypto.aesEncryptPkcs7(btuAesKey, byteArrayOf(0x01, 0x2A, 0x00, 0x00, 0x00))
                    setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, byteArrayOf(0x01, 0x00) + enc)
                }
            }
        }
    }

    /** A BTU already secured, whose validation answer is [response] whatever was written. */
    private fun securedBtuAnswering(response: ByteArray) = MockGattTransport().apply {
        setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x01))
        onCharacteristicWrite = { charUuid, payload ->
            if (charUuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT && payload.size == 18) {
                setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, response)
            }
        }
    }

    private fun establishAgainst(transport: MockGattTransport): Throwable? = runBlocking {
        transport.connect()
        val mac = "11:22:33:44:55:66"
        val keyStore = InMemorySecureKeyStore().apply { saveSessionKey(mac, ByteArray(16) { 0x77 }) }
        DefaultSecuritySessionManager(keyStore).establishSession(mac, transport).exceptionOrNull()
    }

    @Test
    fun validationAnswerOfOneByteIsAnInvalidResponse() {
        val failure = establishAgainst(securedBtuAnswering(byteArrayOf(0x01)))
        assertTrue("got $failure", failure.endedInAValidationFailure())
    }

    @Test
    fun validationAnswerWithNoBytesIsAnInvalidResponse() {
        val failure = establishAgainst(securedBtuAnswering(byteArrayOf()))
        assertTrue("got $failure", failure.endedInAValidationFailure())
    }

    @Test
    fun validationBlobThatIsNotAWholeAesBlockIsAnInvalidResponse() {
        val failure = establishAgainst(securedBtuAnswering(byteArrayOf(0x01, 0x00) + ByteArray(15)))
        assertTrue("got $failure", failure.endedInAValidationFailure())
    }

    /**
     * A key the BTU rejects is forgotten and the cycle runs once more. A BTU still secured with
     * nothing stored left to try asks for pairing again, with the rejection kept as the reason.
     */
    @Test
    fun aRejectedStoredKeyIsForgottenAndTheCycleRunsOnceMore() = runBlocking {
        val mac = "11:22:33:44:55:66"
        val storedKey = ByteArray(16) { 0x77 }
        val rejection = byteArrayOf(0x01, 0x00) +
            SabCrypto.aesEncryptPkcs7(storedKey, byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00))
        val transport = securedBtuAnswering(rejection).apply { connect() }
        val keyStore = InMemorySecureKeyStore().apply { saveSessionKey(mac, storedKey) }

        val failure = DefaultSecuritySessionManager(keyStore).establishSession(mac, transport).exceptionOrNull()

        assertTrue("got $failure", failure is NoStoredKeyException)
        assertTrue(failure.endedInAValidationFailure())
        assertTrue("the rejected key must not survive", !keyStore.hasSessionKey(mac))
    }

    /** An answer that is not a validation at all is read once more before the key is doubted. */
    @Test
    fun anInvalidValidationAnswerIsRetriedOnce() = runBlocking {
        val mac = "11:22:33:44:55:66"
        val storedKey = ByteArray(16) { 0x77 }
        val valid = byteArrayOf(0x01, 0x00) +
            SabCrypto.aesEncryptPkcs7(storedKey, byteArrayOf(0x01, 0x10, 0x00, 0x00, 0x00))
        var validations = 0
        val transport = MockGattTransport().apply {
            connect()
            setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x01))
            onCharacteristicWrite = { uuid, payload ->
                if (uuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT && payload.size == 18) {
                    validations++
                    setCharacteristicValue(
                        SabGattUuids.CHAR_SECURITY_DATA_SOURCE,
                        if (validations == 1) byteArrayOf(0x01) else valid
                    )
                }
            }
        }
        val keyStore = InMemorySecureKeyStore().apply { saveSessionKey(mac, storedKey) }

        val session = DefaultSecuritySessionManager(keyStore).establishSession(mac, transport).getOrThrow()

        assertEquals(16, session.incrementId)
        assertEquals(2, validations)
        assertTrue(keyStore.hasSessionKey(mac))
    }

    /** The BTU that will not exchange keys answers the modulus with one error byte. */
    @Test
    fun secureExchangeAnsweredWithOneErrorByteFails() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()
        transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x00))
        transport.onCharacteristicWrite = { charUuid, payload ->
            if (charUuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT && payload.size == 129) {
                transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, byteArrayOf(0x05))
            }
        }

        val result = DefaultSecuritySessionManager(InMemorySecureKeyStore()).establishSession("AA:BB:CC:11:22:33", transport)

        assertTrue("got ${result.exceptionOrNull()}", result.exceptionOrNull() is SecureExchangeException)
    }

    @Test
    fun secureExchangeAnswerThatIsNotOneRsaBlockFails() = runBlocking {
        val transport = MockGattTransport()
        transport.connect()
        transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x00))
        transport.onCharacteristicWrite = { charUuid, payload ->
            if (charUuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT && payload.size == 129) {
                transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, ByteArray(127))
            }
        }
        val keyStore = InMemorySecureKeyStore()

        val result = DefaultSecuritySessionManager(keyStore).establishSession("AA:BB:CC:11:22:33", transport)

        assertTrue("got ${result.exceptionOrNull()}", result.exceptionOrNull() is SecureExchangeException)
        assertTrue("A failed exchange must not persist a key", !keyStore.hasSessionKey("AA:BB:CC:11:22:33"))
    }

    /** The validation write: int16 LE 1, then AES-128 of [0x03, CRC-16/ARC(0x03) LE] = 03 40 01. */
    @Test
    fun validationWriteGoldenVectorPrefixAndPlaintext() = runBlocking {
        val key = ByteArray(16) { 0x77 }
        var written: ByteArray? = null
        val transport = MockGattTransport()
        transport.connect()
        transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x01))
        transport.onCharacteristicWrite = { charUuid, payload ->
            if (charUuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT) {
                written = payload
                val answer = SabCrypto.aesEncryptPkcs7(key, byteArrayOf(0x01, 0x10, 0x00, 0x00, 0x00))
                transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_DATA_SOURCE, byteArrayOf(0x01, 0x00) + answer)
            }
        }
        val mac = "11:22:33:44:55:66"
        val keyStore = InMemorySecureKeyStore().apply { saveSessionKey(mac, key) }

        DefaultSecuritySessionManager(keyStore).establishSession(mac, transport).getOrThrow()

        val payload = checkNotNull(written)
        assertEquals(18, payload.size)
        assertArrayEquals(byteArrayOf(0x01, 0x00), payload.copyOfRange(0, 2))
        assertArrayEquals(
            byteArrayOf(0x03, 0x40, 0x01),
            SabCrypto.aesDecryptLenient(key, payload.copyOfRange(2, 18))
        )
    }

    /** Golden vectors G8 and G9 end to end: the 18-byte write, and the wire answer it parses. */
    @Test
    fun validationGoldenVectorsG8AndG9() = runBlocking {
        val key = (0 until 16).map { it.toByte() }.toByteArray()
        var written: ByteArray? = null
        val transport = MockGattTransport()
        transport.connect()
        transport.setCharacteristicValue(SabGattUuids.CHAR_SECURITY_STATUS, byteArrayOf(0x01))
        transport.onCharacteristicWrite = { charUuid, payload ->
            if (charUuid == SabGattUuids.CHAR_SECURITY_CONTROL_POINT) {
                written = payload
                transport.setCharacteristicValue(
                    SabGattUuids.CHAR_SECURITY_DATA_SOURCE,
                    "01002B88B13F99E6FE6F65EE92349CBA7095".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                )
            }
        }
        val mac = "11:22:33:44:55:66"
        val keyStore = InMemorySecureKeyStore().apply { saveSessionKey(mac, key) }

        val session = DefaultSecuritySessionManager(keyStore).establishSession(mac, transport).getOrThrow()

        assertArrayEquals(
            "0100DB4BC948F1988C0B82D74D332F9F2275".chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            written
        )
        assertEquals(42, session.incrementId)
    }

    private fun MockGattTransport.readStoredDataSource(): ByteArray =
        runBlocking { readCharacteristic(SabGattUuids.SERVICE_SECURITY, SabGattUuids.CHAR_SECURITY_DATA_SOURCE).getOrThrow() }

    /** The cycle ended, and somewhere along it the BTU refused or garbled the validation. */
    private fun Throwable?.endedInAValidationFailure(): Boolean =
        this is KeyValidationException ||
            (this != null && suppressed.any { it is KeyValidationException })
}
