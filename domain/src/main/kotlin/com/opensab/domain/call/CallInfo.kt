package com.opensab.domain.call

enum class CallStatus {
    IDLE,
    RINGING,
    ACTIVE,
    DISCONNECTED
}

data class CallInfo(
    val callerName: String,
    val callerNumber: String,
    val status: CallStatus = CallStatus.IDLE,
    val timestamp: Long = System.currentTimeMillis()
)
