package com.opensab.sab.session.crypto

data class SecuritySession(
    val macAddress: String,
    val aesKey: ByteArray,
    val isValidated: Boolean = true,
    val incrementId: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SecuritySession
        if (macAddress != other.macAddress) return false
        if (!aesKey.contentEquals(other.aesKey)) return false
        if (isValidated != other.isValidated) return false
        if (incrementId != other.incrementId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = macAddress.hashCode()
        result = 31 * result + aesKey.contentHashCode()
        result = 31 * result + isValidated.hashCode()
        result = 31 * result + (incrementId ?: 0)
        return result
    }
}
