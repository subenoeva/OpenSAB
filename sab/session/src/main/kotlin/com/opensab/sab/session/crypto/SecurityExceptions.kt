package com.opensab.sab.session.crypto

sealed class SecuritySessionException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class NoStoredKeyException(
    message: String = "No stored session key found for this vehicle. Pairing is required."
) : SecuritySessionException(message)

internal class SecureExchangeException(
    message: String = "Secure exchange handshake failed",
    cause: Throwable? = null
) : SecuritySessionException(message, cause)

internal class KeyValidationException(
    message: String = "Session key validation failed",
    cause: Throwable? = null,
    /** The BTU answered and said no, as opposed to an answer that could not be read at all. */
    val rejected: Boolean = false
) : SecuritySessionException(message, cause)
