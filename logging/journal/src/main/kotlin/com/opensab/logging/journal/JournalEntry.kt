package com.opensab.logging.journal

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One call to the log: its first line and every continuation line, as one unit. */
data class JournalEntry(
    val instant: Instant,
    val local: LocalDateTime,
    val priority: Char,
    val tag: String,
    val message: String,
) {
    /** The line that started the entry, for listings that show one row per call. */
    val firstLine: String get() = message.substringBefore('\n')

    /** `yyyy-MM-dd HH:mm:ss.SSS [P/tag] message` plus a newline, the format the file has always had. */
    fun format(): String = "${TIMESTAMP.format(local)} [$priority/$tag] $message\n"

    companion object {
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        fun at(instant: Instant, zone: ZoneId, priority: Char, tag: String, message: String): JournalEntry =
            JournalEntry(instant, LocalDateTime.ofInstant(instant, zone), priority, tag, message)
    }
}
