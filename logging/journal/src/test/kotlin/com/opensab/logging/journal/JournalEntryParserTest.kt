package com.opensab.logging.journal

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JournalEntryParserTest {

    @Test
    fun aLineWithoutADatePrefixBelongsToThePreviousEntry() {
        val entries = JournalEntryParser.parse(sequenceOf(
            "2026-09-14 15:43:51.723 [D/OpenSAB] Notification removed for com.whatsapp: route=MESSAGE, key=0|com.whatsapp|1|abc=",
            "|10271",
            "2026-09-14 15:43:51.734 [D/OpenSAB] Message notification removed for com.whatsapp"
        ), MADRID)
        assertEquals(2, entries.size)
        assertEquals(
            "Notification removed for com.whatsapp: route=MESSAGE, key=0|com.whatsapp|1|abc=\n|10271",
            entries[0].message
        )
    }

    @Test
    fun aStackTraceAndItsBlankLinesStayInItsEntry() {
        val entries = JournalEntryParser.parse(sequenceOf(
            "2026-09-14 14:04:44.959 [W/OpenSAB] ControlPoint notifications subscription terminated with error",
            "kotlinx.coroutines.JobCancellationException: Job was cancelled",
            "",
            "kotlinx.coroutines.JobCancellationException: Job was cancelled",
            "2026-09-14 14:04:44.962 [W/OpenSAB] FunctionNotification observation failed"
        ), MADRID)
        assertEquals(2, entries.size)
        assertEquals(4, entries[0].message.lines().size)
        assertEquals("ControlPoint notifications subscription terminated with error", entries[0].firstLine)
    }

    @Test
    fun linesBeforeTheFirstEntryAreDropped() {
        val entries = JournalEntryParser.parse(sequenceOf(
            "\tat a.b.C.d(C.kt:1)",
            "2026-09-24 20:24:13.772 [I/OpenSAB] SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)"
        ), MADRID)
        assertEquals(1, entries.size)
    }

    @Test
    fun theLocalTimeBecomesAnInstantInTheGivenZone() {
        val entry = JournalEntryParser.parse(sequenceOf(
            "2026-09-24 20:24:13.772 [I/OpenSAB] x"), MADRID).single()
        assertEquals(Instant.parse("2026-09-24T18:24:13.772Z"), entry.instant)
        assertEquals('I', entry.priority)
        assertEquals("OpenSAB", entry.tag)
    }

    @Test
    fun anEntryRoundTripsThroughItsFormat() {
        val entry = JournalEntry.at(Instant.parse("2026-09-24T18:24:13.772Z"), MADRID, 'W', "OpenSAB", "a\nb")
        assertEquals("2026-09-24 20:24:13.772 [W/OpenSAB] a\nb\n", entry.format())
        assertEquals(entry, JournalEntryParser.parse(entry.format().lineSequence(), MADRID).single())
    }

    @Test
    fun theDailyUseGoldenParsesIntoItsEntries() {
        assertEquals(2819, JournalEntryParser.parse(golden("daily-use.log"), MADRID).size)
    }

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aSessionFileIsReadInTheZoneOfItsHeader() {
        val file = tmp.newFile("s.log").apply {
            writeText(
                "2026-09-24 20:24:13.772 [I/Journal] Journal session file opened at " +
                    "2026-09-24T18:24:13.772Z (zone Europe/Madrid, offset +02:00)\n" +
                    "2026-09-24 20:24:13.772 [I/OpenSAB] SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)\n"
            )
        }
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val entries = JournalEntryParser.parseSessionFile(file)
            assertEquals(Instant.parse("2026-09-24T18:24:13.772Z"), entries[1].instant)
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}
