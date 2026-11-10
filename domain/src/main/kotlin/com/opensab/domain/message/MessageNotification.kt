package com.opensab.domain.message

/** One `MessagingStyle` entry. A null [sender] is the rider's own message. */
data class HistoricMessage(
    val sender: String?,
    val text: String,
    val timestampMillis: Long
)

/** A posted notification, stripped of Android. */
data class MessageNotification(
    val packageName: String,
    val key: String,
    val title: String?,
    val text: String?,
    val conversationTitle: String?,
    val isGroupConversation: Boolean,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean,
    val isForegroundService: Boolean = false,
    val postTimeMillis: Long,
    val messages: List<HistoricMessage> = emptyList()
)
