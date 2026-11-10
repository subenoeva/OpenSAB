package com.opensab.domain.message

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** **Owner: the test that constructs it.** */
class MockMessageMenuAccess(var next: MessageMenuGates = MessageMenuGates.CLOSED) : MessageMenuAccess {
    var gatesCalls = 0
        private set

    override suspend fun gates(): MessageMenuGates {
        gatesCalls++
        return next
    }
}

/** **Owner: the test that constructs it.** */
class MockMessageSource : MessageSource {
    private val _currentMessage = MutableStateFlow<MessageInfo?>(null)
    override val currentMessage: StateFlow<MessageInfo?> = _currentMessage.asStateFlow()

    private val _claimingScreen = MutableStateFlow(false)
    override val claimingScreen: StateFlow<Boolean> = _claimingScreen.asStateFlow()

    private val _canReply = MutableStateFlow(false)
    override val canReply: StateFlow<Boolean> = _canReply.asStateFlow()

    var yieldCalls = 0
    var startCalls = 0

    /** Everything handed to [reply], in order. */
    val replied = mutableListOf<String>()

    /** What the next [reply] answers. False is an app that refused the send. */
    var replySucceeds: Boolean = true

    /**
     * When true, [reply] suspends instead of answering: it is the send still in flight, which is
     * the only way to look at the "Sending" face of the reply page. It is never released, because
     * what that face has to prove is that it offers the rider nothing to press.
     */
    var replySuspends: Boolean = false

    fun setMessage(info: MessageInfo?) {
        _currentMessage.value = info
        _claimingScreen.value = info != null
    }

    /** Whether the live notification offers a way back. Independent of [setMessage], as in life. */
    fun setCanReply(canReply: Boolean) {
        _canReply.value = canReply
    }

    override suspend fun reply(text: String): Boolean {
        replied += text
        if (replySuspends) awaitCancellation()
        return replySucceeds
    }

    override fun yieldScreen() {
        yieldCalls++
        _claimingScreen.value = false
    }

    override fun start(scope: CoroutineScope) {
        startCalls++
    }

    override fun stop() {
        _currentMessage.value = null
        _claimingScreen.value = false
        _canReply.value = false
    }
}

/** **Owner: the test that constructs it.** */
class MockSmsSender(var nextResult: Boolean = true) : SmsSender {

    val sentMessages = mutableListOf<Pair<String, String>>()

    val sendCount: Int get() = sentMessages.size

    /**
     * When set, [send] suspends on it instead of answering at once: it is the send still in
     * flight, the only way to look at the "Sending" face on the wire. Completing it lets the
     * send answer [nextResult].
     */
    var inFlight: CompletableDeferred<Unit>? = null

    override suspend fun send(recipientNumber: String, text: String): Boolean {
        sentMessages += recipientNumber to text
        inFlight?.await()
        return nextResult
    }
}
