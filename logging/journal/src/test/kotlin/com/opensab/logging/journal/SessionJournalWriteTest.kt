package com.opensab.logging.journal

import com.opensab.logging.journal.Anchors.MISSING_CLOSED_MARK
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.ZoneId
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionJournalWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root: File by lazy { tmp.newFolder("logs") }
    private val exportDir: File by lazy { tmp.newFolder("exports") }
    private val clock = MutableClock()
    private val executor = ManualJournalExecutor()
    private val journal: SessionJournal by lazy { journal() }

    /** Refuses every write while [failing]; otherwise appends for real. */
    private class FlakyWriter : JournalFileWriter {
        var failing = false
        override fun append(file: File, text: String) {
            if (failing) throw IOException("refused")
            JournalFileWriter.APPENDING.append(file, text)
        }
    }

    private fun journal(writer: JournalFileWriter = JournalFileWriter.APPENDING) = SessionJournal(
        root = root,
        exportDir = exportDir,
        clock = clock,
        zone = { MADRID },
        limitBytes = { 50L shl 20 },
        executor = executor,
        writer = writer,
        summarizer = SessionSummarizer(),
    )

    private fun journalAtEachEntryTime() = journal

    private fun SessionJournal.appendAt(iso: String, p: Char, msg: String) {
        clock.set(iso)
        append(p, "OpenSAB", msg)
    }

    private fun SessionJournal.replay(entry: JournalEntry) {
        clock.set(entry.instant)
        append(entry.priority, entry.tag, entry.message)
    }

    @Test
    fun theDailyUseGoldenIsWrittenExactlyAsTheFixtureSaysItIs() {
        val journal = journalAtEachEntryTime()
        JournalEntryParser.parse(golden("daily-use.log"), MADRID).forEach { journal.replay(it) }
        val expected = SessionFileFixture.fromGolden("daily-use.log")
        expected.forEach { (base, entries) ->
            val file = File(root, "sessions/$base.log")
            assertEquals(base, entries.joinToString("") { it.format() }, file.readText())
        }
        assertTrue(File(root, "days/2026-09-23.log").exists())
        assertTrue(File(root, "days/2026-09-24.log").exists())
    }

    @Test
    fun aNameCollisionGetsASuffix() {
        // The clock went back an hour: same local minute, same process-local number.
        journal.appendAt("2026-10-25T01:30:00Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        journal.appendAt("2026-10-25T01:31:00Z", 'I', "SabSession #1 closed in 5 ms")
        journal.appendAt("2026-10-25T01:30:00Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        val names = File(root, "sessions").list()!!.filter { it.endsWith(".log") }.sorted()
        assertEquals(listOf("2026-10-25_02-30-00_s1-2.log", "2026-10-25_02-30-00_s1.log"), names)
    }

    @Test
    fun aFailedWriteIsReportedOnTheNextSuccessfulOne() {
        val failing = FlakyWriter()
        val journal = journal(writer = failing)
        journal.appendAt("2026-09-24T18:00:00Z", 'I', "before")
        failing.failing = true
        journal.appendAt("2026-09-24T18:00:01Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        journal.appendAt("2026-09-24T18:00:04Z", 'I', "lost too")
        failing.failing = false
        journal.appendAt("2026-09-24T18:00:05Z", 'I', "after")

        assertEquals(0, File(root, "days").listFiles()!!.single().readLines().count { it.contains("after") })
        val session = File(root, "sessions").listFiles()!!.single { it.name.endsWith(".log") }.readLines()
        val drop = session.indexOfFirst { it.contains("Journal dropped") }
        assertTrue(session[drop].contains("[W/OpenSAB] Journal dropped 2 entries between 20:00:01 and 20:00:04"))
        assertTrue(session[drop + 1].endsWith("after"))
    }

    @Test
    fun aClosingWithNoClosedInIsClosedByTheTick() {
        journal.appendAt("2026-09-24T18:00:00Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        journal.appendAt("2026-09-24T18:05:00Z", 'I', "SabSession #1 closing (LINK_LOST), 17 close actions")
        clock.set("2026-09-24T18:05:10Z"); executor.advanceBy(Duration.ofSeconds(10))
        val lines = File(root, "sessions/2026-09-24_20-00-00_s1.log").readLines()
        assertTrue(lines.last().contains("[W/Journal] $MISSING_CLOSED_MARK"))
        journal.appendAt("2026-09-24T18:05:11Z", 'I', "later")
        assertTrue(File(root, "days/2026-09-24.log").readText().contains("later"))
    }

    @Test
    fun theHeaderCarriesTheInstantZoneAndOffset() {
        journal.appendAt("2026-09-24T18:24:13.772Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        assertEquals(
            "2026-09-24 20:24:13.772 [I/Journal] Journal session file opened at 2026-09-24T18:24:13.772Z (zone Europe/Madrid, offset +02:00)",
            File(root, "sessions/2026-09-24_20-24-13_s1.log").readLines().first()
        )
    }

    /**
     * Android's clock reads microseconds; every other stamp in the file is in milliseconds, and so
     * must be the header's instant, which prints the raw [java.time.Instant].
     */
    @Test
    fun theHeaderInstantIsStampedToTheMillisecondWhenTheClockReadsMicroseconds() {
        journal.appendAt("2026-09-24T18:24:13.772123Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        assertEquals(
            "2026-09-24 20:24:13.772 [I/Journal] Journal session file opened at 2026-09-24T18:24:13.772Z (zone Europe/Madrid, offset +02:00)",
            File(root, "sessions/2026-09-24_20-24-13_s1.log").readLines().first()
        )
    }

    @Test
    fun concurrentWritersNeverInterleaveAnEntry() {
        val journal = SessionJournal.create(root, exportDir) { 50L shl 20 }
        val threads = (1..8).map { t -> thread { repeat(500) { journal.append('D', "OpenSAB", "t$t line $it\ncontinuation $it") } } }
        threads.forEach { it.join() }; journal.close()
        // A run that crosses local midnight splits across two day files.
        val lines = File(root, "days").listFiles()!!.sorted().flatMap { it.readLines() }
        val entries = JournalEntryParser.parse(lines.asSequence(), ZoneId.systemDefault())
        assertEquals(4000, entries.size)
        assertTrue(entries.all { it.message.lines().size == 2 })
    }
}
