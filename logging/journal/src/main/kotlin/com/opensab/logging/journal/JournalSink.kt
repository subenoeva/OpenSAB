package com.opensab.logging.journal

/** Where the log hands each call: priority, tag and message, and nothing else. */
fun interface JournalSink {
    fun append(priority: Char, tag: String, message: String)
}
