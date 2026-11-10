package com.opensab.sab.protocol.crypto

/**
 * Extends [Exception] directly, not the sealed `SecuritySessionException`: a CRC failure is
 * wire-format validation, so a `catch (e: SecuritySessionException)` around the handshake will not
 * see this one.
 */
class ChecksumMismatchException(val expected: Int, val actual: Int) :
    Exception("CRC-16 mismatch: expected 0x${expected.toString(16)}, got 0x${actual.toString(16)}")
