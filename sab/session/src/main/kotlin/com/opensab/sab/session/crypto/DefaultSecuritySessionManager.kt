package com.opensab.sab.session.crypto

import com.opensab.logging.Log
import com.opensab.sab.protocol.ble.SabGattUuids
import com.opensab.sab.protocol.crypto.ChecksumMismatchException
import com.opensab.sab.protocol.crypto.Crc16Arc
import com.opensab.sab.protocol.crypto.SabCrypto
import com.opensab.sab.session.ble.GattTransport
import com.opensab.sab.session.coroutines.runCatchingCancellable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey

class DefaultSecuritySessionManager(
    private val keyStore: SecureKeyStore
) : SecuritySessionManager {

    private companion object {
        const val CMD_SECURE_EXCHANGE: Byte = 0x02
        const val CMD_VALIDATION: Byte = 0x03
    }

    override suspend fun establishSession(
        macAddress: String,
        transport: GattTransport
    ): Result<SecuritySession> = runCatchingCancellable {
        Log.d("Establishing secure session for MAC: $macAddress")

        val statusResult = transport.readCharacteristic(
            serviceUuid = SabGattUuids.SERVICE_SECURITY,
            characteristicUuid = SabGattUuids.CHAR_SECURITY_STATUS
        ).getOrThrow()

        val isAlreadySecured = statusResult.isNotEmpty() && statusResult[0] == 0x01.toByte()
        Log.d("Initial SecurityStatus: isAlreadySecured=$isAlreadySecured")

        val aesKey = if (isAlreadySecured) {
            val persistedKey = keyStore.getSessionKey(macAddress)
                ?: throw NoStoredKeyException("BTU is already secured but no stored key found for $macAddress")
            Log.i("BTU is already secured. Reusing persisted session key for $macAddress")
            persistedKey
        } else {
            performSecureExchange(transport)
        }

        val incrementId = validateKeyWithBtu(transport, aesKey)
        Log.i("Session key validated with BTU. Increment ID: $incrementId")

        keyStore.saveSessionKey(macAddress, aesKey)

        SecuritySession(
            macAddress = macAddress,
            aesKey = aesKey,
            isValidated = true,
            incrementId = incrementId
        )
    }

    private suspend fun performSecureExchange(transport: GattTransport): ByteArray {
        Log.i("Starting RSA-1024 secure exchange")
        val keyPair = SabCrypto.generateRsaKeyPair(1024)
        val pubKey = keyPair.public as? RSAPublicKey
            ?: throw SecureExchangeException("Generated key is not an RSAPublicKey")
        val modulus = SabCrypto.extractModulus(pubKey)

        // Write [0x02] + modulus (129 bytes) to CHAR_SECURITY_CONTROL_POINT
        val exchangePayload = ByteArray(1 + modulus.size)
        exchangePayload[0] = CMD_SECURE_EXCHANGE
        System.arraycopy(modulus, 0, exchangePayload, 1, modulus.size)

        transport.writeCharacteristic(
            serviceUuid = SabGattUuids.SERVICE_SECURITY,
            characteristicUuid = SabGattUuids.CHAR_SECURITY_CONTROL_POINT,
            payload = exchangePayload,
            withResponse = true
        ).getOrThrow()

        // Read 128-byte ciphertext from CHAR_SECURITY_DATA_SOURCE
        val dataSourcePayload = transport.readCharacteristic(
            serviceUuid = SabGattUuids.SERVICE_SECURITY,
            characteristicUuid = SabGattUuids.CHAR_SECURITY_DATA_SOURCE
        ).getOrThrow()

        if (dataSourcePayload.size == 1) {
            throw SecureExchangeException("Invalid secure exchange response from BTU (error byte: 0x${dataSourcePayload[0].toString(16)})")
        }
        if (dataSourcePayload.size != 128) {
            throw SecureExchangeException("Invalid DataSource payload length: expected 128 bytes, got ${dataSourcePayload.size}")
        }

        // Decrypt 128-byte block with RSA private key
        val decrypted = SabCrypto.rsaDecrypt(keyPair.private, dataSourcePayload)
        if (decrypted.size < 128) {
            throw SecureExchangeException("Decrypted RSA block is too short (${decrypted.size} bytes)")
        }

        val aesKey = decrypted.copyOfRange(110, 126)
        val receivedCrc = decrypted.copyOfRange(126, 128)
        val computedCrc = Crc16Arc.calculateLe(aesKey)

        if (!computedCrc.contentEquals(receivedCrc)) {
            val exp = (receivedCrc[0].toInt() and 0xFF) or ((receivedCrc[1].toInt() and 0xFF) shl 8)
            val act = (computedCrc[0].toInt() and 0xFF) or ((computedCrc[1].toInt() and 0xFF) shl 8)
            throw ChecksumMismatchException(exp, act)
        }

        Log.i("Successfully completed RSA exchange and verified AES-128 key CRC")
        return aesKey
    }

    private suspend fun validateKeyWithBtu(transport: GattTransport, aesKey: ByteArray): Int {
        require(aesKey.size == 16) { "Invalid AES key length: expected 16, got ${aesKey.size}" }

        // Validation plaintext command: [0x03, CRC_L, CRC_H] (3 bytes: 0x03, 0x40, 0x01)
        val cmd = byteArrayOf(CMD_VALIDATION) + Crc16Arc.calculateLe(byteArrayOf(CMD_VALIDATION))
        val encryptedCmd = SabCrypto.aesEncryptPkcs7(aesKey, cmd)

        // Write payload: LE int16(1) (2 bytes) + 16 bytes encrypted command = 18 bytes
        val writePayload = ByteBuffer.allocate(2 + encryptedCmd.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1.toShort())
            .put(encryptedCmd)
            .array()

        transport.writeCharacteristic(
            serviceUuid = SabGattUuids.SERVICE_SECURITY,
            characteristicUuid = SabGattUuids.CHAR_SECURITY_CONTROL_POINT,
            payload = writePayload,
            withResponse = true
        ).getOrThrow()

        val resp = transport.readCharacteristic(
            serviceUuid = SabGattUuids.SERVICE_SECURITY,
            characteristicUuid = SabGattUuids.CHAR_SECURITY_DATA_SOURCE
        ).getOrThrow()

        if (resp.size <= 2) {
            throw KeyValidationException("Validation response too short (${resp.size} bytes)")
        }

        // Skip 2-byte prefix and decrypt with AES
        val ciphertext = resp.copyOfRange(2, resp.size)
        val decrypted = SabCrypto.aesDecryptLenient(aesKey, ciphertext)

        if (decrypted.size < 5) {
            throw KeyValidationException("Decrypted validation payload too short (${decrypted.size} bytes)")
        }

        val buf = ByteBuffer.wrap(decrypted).order(ByteOrder.LITTLE_ENDIAN)
        val status = buf.get().toInt() and 0xFF
        if (status != 1) {
            throw KeyValidationException("Validation rejected by BTU with status code $status")
        }

        return buf.int
    }

    override suspend fun validateExistingKey(
        macAddress: String,
        transport: GattTransport,
        existingKey: ByteArray
    ): Result<SecuritySession> = runCatchingCancellable {
        if (existingKey.size != 16) {
            throw KeyValidationException("Invalid key size: expected 16 bytes, got ${existingKey.size}")
        }
        val incrementId = validateKeyWithBtu(transport, existingKey)
        keyStore.saveSessionKey(macAddress, existingKey)
        SecuritySession(macAddress, existingKey, isValidated = true, incrementId = incrementId)
    }
}

