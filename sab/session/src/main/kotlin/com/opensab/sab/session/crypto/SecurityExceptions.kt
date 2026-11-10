package com.opensab.sab.session.crypto

sealed class SecuritySessionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The BTU's "already secured" outcome, part of the protocol's failure set; no path raises it today. */
@Suppress("unused")
internal class AlreadySecuredException : SecuritySessionException("BTU is already secured from a previous session")

internal class NoStoredKeyException(
    message: String = "No stored session key found for this vehicle. Pairing is required."
) : SecuritySessionException(message)

internal class SecureExchangeException(
    message: String = "Secure exchange handshake failed",
    cause: Throwable? = null
) : SecuritySessionException(message, cause)

internal class KeyValidationException(
    message: String = "Session key validation failed",
    cause: Throwable? = null
) : SecuritySessionException(message, cause)
