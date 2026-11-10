package com.opensab.domain.message

data class MessageInfo(
    val sender: String,
    val body: String,
    val appName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
