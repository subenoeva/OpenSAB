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
}

