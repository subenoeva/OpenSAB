package com.opensab.domain.message

import com.opensab.domain.notifications.MessagingPackages

/**
 * Returning null is a first-class answer, not a failure: most of what a messaging app posts is not
 * a message the rider needs read to them at 90 km/h, and the whole reason this lives in pure Kotlin
 * is that the suite gets to prove which is which.
 */
internal object MessageNotificationParser {

    fun parse(notification: MessageNotification): MessageInfo? {
        if (!MessagingPackages.isMessaging(notification.packageName)) return null

        // The stacked "3 new messages" summary carries no body of its own; its children do.
        if (notification.isGroupSummary) return null

        // Chat heads and "backup in progress" are ongoing notifications, not messages.
        if (notification.isOngoing) return null

        // A foreground service's notification ("Checking for new messages") is not marked ongoing
        // since Android 14, so the ongoing check alone lets it through.
        if (notification.isForegroundService) return null

        val last = notification.messages.lastOrNull()

        // A `MessagingStyle` entry with no Person is the rider's own. If the last one is theirs,
        // they have just answered from the phone and there is nothing left to read out — this is
        // the single largest source of noise on the cluster.
        if (last != null && last.sender == null) return null

        val conversation = notification.conversationTitle?.trim()?.takeIf { it.isNotEmpty() }
        val title = notification.title?.trim()?.takeIf { it.isNotEmpty() }

        val sender: String?
        val body: String?
        if (last != null) {
            val text = last.text.trim().takeIf { it.isNotEmpty() }
            if (notification.isGroupConversation) {
                sender = conversation ?: title
                body = text?.let { "${last.sender}: $it" }
            } else {
                sender = last.sender?.trim()?.takeIf { it.isNotEmpty() } ?: conversation ?: title
                body = text
            }
        } else {
            sender = conversation ?: title
            body = notification.text?.trim()?.takeIf { it.isNotEmpty() }
        }

        if (body == null) return null

        return MessageInfo(
            sender = sender.orEmpty(),
            body = body,
            appName = MessagingPackages.labelOf(notification.packageName),
            timestamp = last?.timestampMillis ?: notification.postTimeMillis
        )
    }
}
