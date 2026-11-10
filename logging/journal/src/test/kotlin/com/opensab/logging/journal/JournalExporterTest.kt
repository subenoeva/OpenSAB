package com.opensab.logging.journal

import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JournalExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root: File by lazy { tmp.newFolder("logs") }
    private val exportDir: File by lazy { tmp.newFolder("exports") }
    private val clock = MutableClock()
    private val executor = ManualJournalExecutor()
    private val journal: SessionJournal by lazy { journal() }
    private val request = ExportRequest(listOf(BASE_S1), includeDays = false)

    private fun journal() = SessionJournal(
        root = root,
        exportDir = exportDir,
        clock = clock,
        zone = { MADRID },
        limitBytes = { 50L shl 20 },
        executor = executor,
        writer = JournalFileWriter.APPENDING,
        summarizer = SessionSummarizer(),
    )

    private fun SessionJournal.appendAt(iso: String, p: Char, msg: String) {
        clock.set(iso)
        append(p, "OpenSAB", msg)
    }

    /** A session file as a previous process left it, opened at a fixed Madrid time. */
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

    @Before
    fun setUp() {
        writeSessionFile(BASE_S1, closed = true)
        writeSessionFile(BASE_S2, closed = true)
        journal // forces the startup sweep to queue, so the fixtures above get a .json
        executor.runAll()
        File(root, "days").mkdirs()
        File(root, "days/2026-09-24.log")
            .writeText(JournalEntry.at(Instant.parse("2026-09-24T12:00:00Z"), MADRID, 'I', "OpenSAB", "a day entry").format())
    }

    private fun exportResult(req: ExportRequest): Result<File> {
        var result: Result<File>? = null
        journal.export(req) { result = it }
        executor.runAll()
        return result!!
    }

    /** [JournalExporter.zip] writes with the real wall clock, so the mtime is pinned to the test's virtual [clock]. */
    private fun exportAndWait(req: ExportRequest): File {
        val zip = exportResult(req).getOrThrow()
        zip.setLastModified(clock.instant().toEpochMilli())
        return zip
    }

    private fun zipEntries(zip: File): Set<String> =
        ZipFile(zip).use { it.entries().asSequence().map(ZipEntry::getName).toSet() }

    @Test
    fun aSessionExportCarriesOnlyItsLogAndJson() {
        val zip = exportAndWait(ExportRequest(listOf(BASE_S1), includeDays = false))
        assertEquals(setOf("sessions/$BASE_S1.log", "sessions/$BASE_S1.json"), zipEntries(zip))
    }

    @Test
    fun shareAllAddsTheDays() {
        val zip = exportAndWait(ExportRequest(listOf(BASE_S1, BASE_S2), includeDays = true))
        assertTrue("days/2026-09-24.log" in zipEntries(zip))
    }

    @Test
    fun aFreshZipSurvivesTheNextExport() {
        val first = exportAndWait(request)
        clock.advance(Duration.ofMinutes(59))
        val second = exportAndWait(request)
        assertTrue(first.exists())
        clock.advance(Duration.ofMinutes(2))
        exportAndWait(request)
        assertFalse(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun twoExportsInTheSameSecondGetDistinctZips() {
        val first = exportAndWait(request)
        val second = exportAndWait(request)
        assertTrue(first.exists())
        assertTrue(second.exists())
        assertFalse(first.name == second.name)
    }

    @Test
    fun theOpenSessionIsNotExported() {
        journal.appendAt("2026-09-24T10:00:00Z", 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        val result = exportResult(ExportRequest(listOf("2026-09-24_12-00-00_s1"), false))
        assertTrue(result.isFailure)
    }

    private companion object {
        const val BASE_S1 = "2026-09-24_20-00-00_s1"
        const val BASE_S2 = "2026-09-24_21-00-00_s2"
    }
}
