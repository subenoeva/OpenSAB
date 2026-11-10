package com.opensab.domain.call

import kotlinx.coroutines.flow.StateFlow

interface TelecomAdapter {

    val currentCall: StateFlow<CallInfo?>

    fun acceptCall()
    fun rejectCall()
    fun endCall()
}
