package com.opensab.domain.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives a call into the source the way the phone does: through the notification bus.
 *
 * **Owner: the test that constructs it.**
 *
 * `DefaultCallManager` is the single owner of the live call, so a `TelecomAdapter` double cannot
 * put one there: the way in is the bus, in the platform's own terms.
 */
class CallNotificationDriver(private val bus: CallNotificationBus) {

    /** The call rings. [callType] is 1 while ringing and 2 once it has been answered. */
    fun post(
        callerName: String,
        callerNumber: String = "",
        callType: Int = 1,
        key: String = KEY,
        commands: CallCommands = NoCallCommands
    ) = bus.post(
        CallNotificationEvent.Posted(
            notification = CallNotification(
                key = key,
                packageName = "com.google.android.dialer",
                category = "call",
                template = "android.app.Notification\$CallStyle",
                callType = callType,
                title = callerName,
                personName = callerName,
                personUri = callerNumber.takeIf { it.isNotEmpty() }?.let { "tel:$it" }
            ),
            commands = commands
        )
    )

    /** However the call ended, the dialer retires the notification. */
    fun remove(key: String = KEY) = bus.post(CallNotificationEvent.Removed(key))

    companion object {
        const val KEY = "0|com.google.android.dialer|1|null|10137"
    }

    object NoCallCommands : CallCommands {
        override fun answer() = Unit
        override fun reject() = Unit
        override fun disconnect() = Unit
    }
}

/** **Owner: the test that constructs it.** */
class MockTelecomAdapter : TelecomAdapter {

    private val _currentCall = MutableStateFlow<CallInfo?>(null)
    override val currentCall: StateFlow<CallInfo?> = _currentCall.asStateFlow()

    fun setIncomingCall(call: CallInfo) {
        _currentCall.value = call.copy(status = CallStatus.RINGING)
    }

    fun clearCall() {
        _currentCall.value = null
    }

    override fun acceptCall() {
        val call = _currentCall.value ?: return
        _currentCall.value = call.copy(status = CallStatus.ACTIVE)
    }

    override fun rejectCall() {
        val call = _currentCall.value ?: return
        _currentCall.value = call.copy(status = CallStatus.DISCONNECTED)
    }

    override fun endCall() {
        val call = _currentCall.value ?: return
        _currentCall.value = call.copy(status = CallStatus.DISCONNECTED)
    }
}

/**
 * Counting wrapper over [MockTelecomAdapter], which exposes no counters of its own: its methods
 * only mutate the call state. Delegating keeps the state machine of the real mock
 * intact while giving the mirror contract harness the record of calls it needs.
 *
 * **Owner: the mirror contract harness.**
 *
 * A call is counted when the page issues it, whether or not the delegate had a call to act on.
 */
class RecordingTelecomAdapter : TelecomAdapter {

    private val delegate = MockTelecomAdapter()

    override val currentCall: StateFlow<CallInfo?> = delegate.currentCall

    var acceptCalls = 0
        private set
    var rejectCalls = 0
        private set
    var endCalls = 0
        private set

    val totalCalls: Int
        get() = acceptCalls + rejectCalls + endCalls

    fun setIncomingCall(call: CallInfo) = delegate.setIncomingCall(call)

    /** Drops the record of calls used to drive the adapter into a given state during setup. */
    fun resetCounts() {
        acceptCalls = 0
        rejectCalls = 0
        endCalls = 0
    }

    override fun acceptCall() {
        acceptCalls++
        delegate.acceptCall()
    }

    override fun rejectCall() {
        rejectCalls++
        delegate.rejectCall()
    }

    override fun endCall() {
        endCalls++
        delegate.endCall()
    }
}
