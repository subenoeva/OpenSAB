package com.opensab.logging.journal

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionJournalLifecycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root: File by lazy { tmp.newFolder("logs") }
    private val exportDir: File by lazy { tmp.newFolder("exports") }
    private val clock = MutableClock()
    private val executor = ManualJournalExecutor()
    private var limit: Long = 50L shl 20
    private val journal: SessionJournal by lazy { journal() }

    private fun journal(
        executor: JournalExecutor = this.executor,
        summarizer: SessionSummarizer = SessionSummarizer(),
        limitBytes: () -> Long = { limit },
        zone: () -> ZoneId = { MADRID },
    ) = SessionJournal(
        root = root,
        exportDir = exportDir,
        clock = clock,
        zone = zone,
        limitBytes = limitBytes,
        executor = executor,
        writer = JournalFileWriter.APPENDING,
        summarizer = summarizer,
    )

    private fun SessionJournal.appendAt(iso: String, p: Char, msg: String) {
        clock.set(iso)
        append(p, "OpenSAB", msg)
    }

    /** A session file as a previous process left it, opened at the base name's Madrid time. */
    private fun writeSessionFile(base: String, closed: Boolean) {
        val opened = Instant.parse("2026-09-24T18:00:00Z")
        fun entry(at: Instant, tag: String, message: String) = JournalEntry.at(at, MADRID, 'I', tag, message).format()
        val text = buildString {
            append(entry(opened, Anchors.JOURNAL_TAG, "${Anchors.HEADER_PREFIX}$opened (zone ${MADRID.id}, offset +02:00)"))
            append(entry(opened, Anchors.JOURNAL_TAG, Anchors.PROLOGUE_MARK))
            append(entry(opened, Anchors.JOURNAL_TAG, Anchors.SESSION_MARK))
            append(entry(opened, "OpenSAB", "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)"))
            if (closed) append(entry(opened.plusSeconds(1), "OpenSAB", "SabSession #1 closed in 5 ms"))
        }
        File(root, "sessions").mkdirs()
        File(root, "sessions/$base.log").writeText(text)
    }

    private fun readSummary(base: String): SessionSummary =
        SessionSummaryCodec.decode(File(root, "sessions/$base.json").readText())!!

    @Test
    fun closingASessionWritesItsSummary() {
        journal.appendAt("2026-09-24T18:00:00Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        journal.appendAt("2026-09-24T18:00:01Z", 'I', "SabSession #1 closed in 5 ms")
        executor.runAll()
        val json = File(root, "sessions/2026-09-24_20-00-00_s1.json")
        assertEquals(CloseKind.CLOSED, SessionSummaryCodec.decode(json.readText())!!.closeKind)
    }

    @Test
    fun aSessionStillClosingWhenTheNextOneOpensIsSummarizedAsClosed() {
        journal.appendAt("2026-09-25T19:20:27.334Z", 'I', "SabSession #2 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        journal.appendAt("2026-09-25T19:28:17.093Z", 'I', "SabSession #2 closing (LINK_LOST), 17 close actions")
        journal.appendAt("2026-09-25T19:28:17.221Z", 'I', "SabSession #3 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        journal.appendAt("2026-09-25T19:28:17.227Z", 'I', "SabSession #2 closed in 134 ms")
        executor.runAll()

        val closing = File(root, "sessions/2026-09-25_21-20-27_s2.log").readText()
        assertTrue(closing.contains("SabSession #2 closed in 134 ms"))
        assertFalse(closing.contains(Anchors.MISSING_CLOSED_MARK))
        assertFalse(File(root, "sessions/2026-09-25_21-28-17_s3.log").readText().contains("SabSession #2 closed in"))
        val summary = readSummary("2026-09-25_21-20-27_s2")
        assertEquals(CloseKind.CLOSED, summary.closeKind)
        assertEquals(134L, summary.closedInMs)
    }

    @Test
    fun aSessionLeftOpenByADeadProcessIsSummarizedAsTruncated() {
        val first = journal()
        first.appendAt("2026-09-24T18:00:00Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        // The process dies here: no close, no executor run.
        val restarted = ManualJournalExecutor()
        val second = journal(executor = restarted)
        second.appendAt("2026-09-24T19:00:00Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        restarted.runAll()
        val old = SessionSummaryCodec.decode(File(root, "sessions/2026-09-24_20-00-00_s1.json").readText())!!
        assertEquals(CloseKind.TRUNCATED, old.closeKind)
        assertTrue(Attention.TRUNCATED in old.attention)
        assertFalse(File(root, "sessions/2026-09-24_21-00-00_s1.json").exists())   // still open, not swept
    }

    @Test
    fun aProcessThatDiedWhileSummarizingIsSummarizedNormally() {
        writeSessionFile("2026-09-24_20-00-00_s1", closed = true)   // .log with closed in, no .json
        val restarted = ManualJournalExecutor()
        journal(executor = restarted)
        restarted.runAll()
        assertEquals(CloseKind.CLOSED, readSummary("2026-09-24_20-00-00_s1").closeKind)
    }

    @Test
    fun listingReSummarizesAnOutdatedOrFailedJson() {
        writeSessionFile("2026-09-24_20-00-00_s1", closed = true)
        File(root, "sessions/2026-09-24_20-00-00_s1.json").writeText("""{"summarizerVersion":0,"baseName":"2026-09-24_20-00-00_s1"}""")
        var listed: List<SessionListing>? = null
        journal.listSessions { listed = it }; executor.runAll()
        assertEquals(SUMMARIZER_VERSION, listed!!.single().summary!!.summarizerVersion)
        assertEquals(SUMMARIZER_VERSION, readSummary("2026-09-24_20-00-00_s1").summarizerVersion)
    }

    @Test
    fun theOpenSessionIsListedFirstWithoutASummary() {
        journal.appendAt("2026-09-24T18:00:00Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        journal.appendAt("2026-09-24T18:00:01Z", 'I', "SabSession #1 closed in 5 ms")
        journal.appendAt("2026-09-24T18:10:00Z", 'I', "SabSession #2 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        var listed: List<SessionListing>? = null
        journal.listSessions { listed = it }; executor.runAll()
        assertEquals(listOf(true, false), listed!!.map { it.isOpen })
        assertNull(listed.first().summary)
    }

    /**
     * Models a session finishing its open on another thread in the gap between the listing's two
     * snapshots, using [SessionJournal.listingSnapshotGap] to land the open exactly there without a
     * real race.
     */
    @Test
    fun aSessionThatFinishesOpeningBetweenTheListingsTwoSnapshotsIsNeverShownAsClosed() {
        journal.appendAt("2026-09-24T18:00:00Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        journal.appendAt("2026-09-24T18:00:01Z", 'I', "SabSession #1 closed in 5 ms")
        executor.runAll()

        journal.listingSnapshotGap = {
            journal.appendAt("2026-09-24T18:10:00Z", 'I', "SabSession #2 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        }
        var listed: List<SessionListing>? = null
        journal.listSessions { listed = it }
        executor.runAll()

        val session2 = listed!!.first { it.baseName.endsWith("_s2") }
        assertTrue(session2.isOpen)
        assertNull(session2.summary)
    }

    @Test
    fun aSummaryThatFailsIsStoredAsFailed() {
        val journal = journal(summarizer = object : SessionSummarizer() {
            override fun summarize(baseName: String, entries: List<JournalEntry>): SessionSummary = error("boom")
        })
        journal.appendAt("2026-09-24T18:00:00Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        journal.appendAt("2026-09-24T18:00:01Z", 'I', "SabSession #1 closed in 5 ms")
        executor.runAll()
        val s = readSummary("2026-09-24_20-00-00_s1")
        assertTrue(Attention.SUMMARY_FAILED in s.attention)
        assertEquals("java.lang.IllegalStateException: boom", s.failure!!.exception)
    }

    /** The screen suspends until it is answered: a listing that throws must still answer. */
    @Test
    fun aListingThatThrowsStillAnswersWithNothing() {
        for (base in listOf("2026-09-24_20-00-00_s1", "2026-09-24_21-00-00_s2")) {
            writeSessionFile(base, closed = true)
            File(root, "sessions/$base.json")
                .writeText("""{"summarizerVersion":$SUMMARIZER_VERSION,"baseName":"$base","openedAt":"not an instant"}""")
        }
        var listed: List<SessionListing>? = null
        journal.listSessions { listed = it }; executor.runAll()
        assertEquals(emptyList<SessionListing>(), listed)
    }

    @Test
    fun anExportThatThrowsStillAnswersWithAFailure() {
        var zoneFails = false
        val journal = journal(zone = { if (zoneFails) throw IllegalStateException("no zone") else MADRID })
        executor.runAll() // the startup sweep reads the zone too
        zoneFails = true
        var exported: Result<File>? = null
        journal.export(ExportRequest(emptyList(), includeDays = false)) { exported = it }; executor.runAll()
        assertTrue(exported!!.isFailure)
    }

    @Test
    fun aClosedJournalStillAnswersBothRequests() {
        val journal = journal(executor = JournalExecutor.singleThread())
        journal.close()
        var listed: List<SessionListing>? = null
        var exported: Result<File>? = null
        journal.listSessions { listed = it }
        journal.export(ExportRequest(emptyList(), includeDays = false)) { exported = it }
        assertEquals(emptyList<SessionListing>(), listed)
        assertTrue(exported!!.isFailure)
    }

    @Test
    fun theNoJournalListsNothing() {
        var listed: List<SessionListing>? = null
        NoSessionJournal.listSessions { listed = it }
        assertEquals(emptyList<SessionListing>(), listed)
    }

    @Test
    fun usageIsReportedOverTheLimitWhenNothingCanGo() {
        limit = 1L
        journal.appendAt("2026-09-24T17:59:59Z", 'I', "a day entry")   // days/2026-09-24.log: today, protected
        journal.appendAt("2026-09-24T18:00:00Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        journal.appendAt("2026-09-24T18:00:01Z", 'I', "SabSession #1 closed in 5 ms")
        val seen = mutableListOf<JournalUsage>()
        journal.setUsageListener { seen += it }
        executor.runAll()
        // The closed session went; today's day file stays and keeps usage over the limit.
        assertFalse(File(root, "sessions/2026-09-24_20-00-00_s1.log").exists())
        assertTrue(seen.last().overLimit)
    }

    @Test
    fun theLimitIsReadOnlyOnTheExecutor() {
        val caller = AtomicReference<Thread>()
        val journal = journal(limitBytes = { caller.set(Thread.currentThread()); 50L shl 20 }, executor = JournalExecutor.singleThread())
        journal.append('I', "OpenSAB", "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        journal.append('I', "OpenSAB", "SabSession #1 closed in 5 ms")
        journal.close()   // shuts the executor down and waits for the retention pass
        assertEquals("session-journal", caller.get().name)
    }

    @Test
    fun theStartupSweepNeverLeavesAnOrphanSummary() {
        // Three sessions from a dead process, none summarized yet. An ancient mtime stands in for
        // "not summarized yet" so whichever is processed first can never look newest by sortKey.
        val bases = listOf("2026-09-24_10-00-00_s1", "2026-09-24_11-00-00_s1", "2026-09-24_12-00-00_s1")
        bases.forEach { writeSessionFile(it, closed = true) }
        val ancient = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli()
        bases.forEach { File(root, "sessions/$it.log").setLastModified(ancient) }
        val restarted = ManualJournalExecutor()
        journal(executor = restarted, limitBytes = { 1L })
        restarted.runAll()
        val sessionsDir = File(root, "sessions")
        sessionsDir.listFiles { file -> file.name.endsWith(".json") }!!.forEach { json ->
            assertTrue("${json.name} has no matching .log", File(sessionsDir, "${json.nameWithoutExtension}.log").exists())
        }
    }

    @Test
    fun aStrayDayFileNameDoesNotBreakRetention() {
        File(root, "days").mkdirs()
        File(root, "days/not-a-date.log").writeText("garbage")
        val restarted = ManualJournalExecutor()
        journal(executor = restarted, limitBytes = { 1L })
        restarted.runAll()   // must not throw
    }

    @Test
    fun aMalformedSummaryDoesNotBreakRetention() {
        writeSessionFile("2026-09-24_20-00-00_s1", closed = true)
        File(root, "sessions/2026-09-24_20-00-00_s1.json")
            .writeText("""{"summarizerVersion":1,"baseName":"x","openedAt":"not-an-instant"}""")
        val restarted = ManualJournalExecutor()
        journal(executor = restarted, limitBytes = { 1L })
        restarted.runAll()   // must not throw
    }
}
