package com.opensab.domain.message

/** Implemented by `AndroidSmsSender` (`:app`). */
interface SmsSender {

    /**
     * `true` only once the telephony layer confirmed the send; `false` on any failure (no SMS
     * capability, permission denied, failure broadcast, timeout).
     */
    suspend fun send(recipientNumber: String, text: String): Boolean
}
