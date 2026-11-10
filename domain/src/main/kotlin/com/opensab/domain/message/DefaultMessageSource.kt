package com.opensab.domain.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.opensab.logging.Log

/**
 * Single owner of two things, and of nothing else: the unread message and its claim on the screen.
 * The MESSAGE bit of the status bar is the cluster's, lit from [currentMessage] by `MessageIndicator`.
 *
 * Splitting the message from the claim is what lets `@Back` and `@Menu` give back the screen
 * without throwing the message away: the bit stays lit. So [yieldScreen] moves [claimingScreen] and
 * never touches [currentMessage]; only the phone retiring the notification does that.
 *
 * It does not listen to the handlebar and it does not write frames. The message reaches the rider
 * as a page on the interrupt stack, and **that page owns the press** — the same rule that stopped
 * `CallManager` from being a second owner of `SELECT`.
 *
 * @param notifications one instance per graph, built by `NotificationModule`.
 */
class DefaultMessageSource(
    private val notifications: MessageNotificationBus
) : MessageSource {

    private val _currentMessage = MutableStateFlow<MessageInfo?>(null)
    override val currentMessage: StateFlow<MessageInfo?> = _currentMessage.asStateFlow()

    private val _claimingScreen = MutableStateFlow(false)
    override val claimingScreen: StateFlow<Boolean> = _claimingScreen.asStateFlow()

    private val _canReply = MutableStateFlow(false)
    override val canReply: StateFlow<Boolean> = _canReply.asStateFlow()

    private var scope: CoroutineScope? = null
    private var busJob: Job? = null

    /**
     * Messaging apps repost the very same notification whenever their own UI refreshes, and that
     * cannot take the screen away from the rider a second time. Same key, same body and same
     * timestamp is a repost; the same key with a new body is the next message in the thread.
     */
    private var lastKey: String? = null
    private var lastBody: String? = null
    private var lastTimestamp: Long? = null

    /**
     * The way back into the live notification, owned here and nowhere else.
     *
     * It is refreshed on **every** [MessageNotificationEvent.Posted], repost included, and that is
     * the one thing this field must get right: the handle belongs to the notification and not to
     * the thread, so the `PendingIntent` of the previous post may already have been cancelled by
     * the app. The dedup below decides whether the rider is shown a new message; it does not decide
     * which handle is live.
     *
     * Safe as a plain `var`: everything that writes it runs in [busJob], which is one collector on
     * one scope.
     */
    private var reply: MessageReplyCommands? = null

    override fun start(scope: CoroutineScope) {
        if (this.scope != null) {
            Log.d("Message source already started")
            return
        }
        this.scope = scope
        Log.i("Starting MessageSource service")

        busJob = scope.launch {
            notifications.events.collect { onEvent(it) }
        }
    }

    override fun yieldScreen() {
        Log.d("Message page yielded the screen, the message stays unread")
        _claimingScreen.value = false
    }

    override fun stop() {
        busJob?.cancel()
        busJob = null
        forget()
        scope = null
    }

    override suspend fun reply(text: String): Boolean {
        val commands = reply
        if (commands == null) {
            Log.w("Asked to reply with no live notification to reply through")
            return false
        }
        return commands.reply(text)
    }

    private fun onEvent(event: MessageNotificationEvent) = when (event) {
        is MessageNotificationEvent.Posted -> onPosted(event.notification, event.reply)
        is MessageNotificationEvent.Removed -> onRemoved(event.key)
        MessageNotificationEvent.ListenerDisconnected -> {
            Log.w("Notification listener disconnected, dropping the unread message")
            forget()
        }
    }

    private fun onPosted(notification: MessageNotification, reply: MessageReplyCommands?) {
        val message = MessageNotificationParser.parse(notification) ?: return

        // Before the dedup, deliberately. A repost the rider never sees still carries the live
        // handle, and the one it replaces may already be dead — the button on the page he has been
        // looking at all along has to keep working.
        setReply(reply)

        if (notification.key == lastKey &&
            message.body == lastBody &&
            message.timestamp == lastTimestamp
        ) {
            Log.d("Ignoring a repost of %s", notification.key)
            return
        }

        Log.d("Message from %s on %s", message.sender, message.appName)
        lastKey = notification.key
        lastBody = message.body
        lastTimestamp = message.timestamp
        _currentMessage.value = message
        _claimingScreen.value = true
    }

    private fun onRemoved(key: String) {
        if (key != lastKey) return
        Log.d("Message thread %s retired on the phone", key)
        forget()
    }

    private fun forget() {
        lastKey = null
        lastBody = null
        lastTimestamp = null
        setReply(null)
        _currentMessage.value = null
        _claimingScreen.value = false
    }

    private fun setReply(commands: MessageReplyCommands?) {
        reply = commands
        _canReply.value = commands != null
    }
}
