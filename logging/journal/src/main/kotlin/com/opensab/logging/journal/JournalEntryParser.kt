package com.opensab.logging.journal

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

object JournalEntryParser {

    private val HEADER =
        Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[([VDIWEA?])/([^\]]*)\] ?(.*)$""")
    private val ZONE_HEADER = Regex("""^Journal session file opened at (\S+) \(zone ([^,]+), offset""")

    /** Lines before the first dated one are dropped: they belong to an entry this file does not have. */
    fun parse(lines: Sequence<String>, zone: ZoneId): List<JournalEntry> {
        val entries = mutableListOf<JournalEntry>()
        var header: MatchResult? = null
        val message = StringBuilder()

        fun flush() {
            val h = header ?: return
            val local = LocalDateTime.parse(h.groupValues[1], JournalEntry.TIMESTAMP)
            entries += JournalEntry(
                instant = local.atZone(zone).toInstant(),
                local = local,
                priority = h.groupValues[2][0],
                tag = h.groupValues[3],
                message = message.toString(),
            )
        }

        fun consume(line: String) {
            val match = HEADER.matchEntire(line)
            if (match != null) {
                flush()
                header = match
                message.setLength(0)
                message.append(match.groupValues[4])
            } else if (header != null) {
                message.append('\n').append(line)
            }
        }

        // One line of lookahead: a trailing empty element is what String.lineSequence() (not a
        // reader, which never produces it) yields for the newline JournalEntry.format() always ends
        // with, not a blank line the caller wrote, so it is dropped rather than consumed.
        var previous: String? = null
        for (line in lines) {
            previous?.let(::consume)
            previous = line
        }
        if (!previous.isNullOrEmpty()) consume(previous)
        flush()
        return entries
    }

    /** Reads the zone from the journal header when there is one, else [ZoneId.systemDefault]. */
    fun parseSessionFile(file: File): List<JournalEntry> {
        val lines = file.readLines(Charsets.UTF_8)
        val zone = lines.firstNotNullOfOrNull { line ->
            HEADER.matchEntire(line)?.groupValues?.get(4)?.let(ZONE_HEADER::find)
        }?.let { ZoneId.of(it.groupValues[2]) } ?: ZoneId.systemDefault()
        return parse(lines.asSequence(), zone)
    }
}
