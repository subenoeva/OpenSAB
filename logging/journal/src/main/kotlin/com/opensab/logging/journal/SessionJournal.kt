package com.opensab.logging.journal

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.SerializationException

/** Something that can fail to write: production appends to the file, tests can refuse. */
fun interface JournalFileWriter {
    fun append(file: File, text: String)

    companion object {
        /** One open, append and close per call: nothing is left open if the process dies. */
        val APPENDING: JournalFileWriter = JournalFileWriter { file, text ->
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }
}

/**
 * Sole owner of logs/: the only thing that writes, closes, summarizes, retains, exports or deletes
 * there. One instance per process, created by OpenSabApplication. The write lock covers the clock
 * read, the router and the write, so a file's order is its timestamps' order. Never logs through
 * Log: it would be writing to itself.
 *
 * Owner of [writeLock], of the [router] it guards, of the dropped-entry count and of [executor],
 * which it shuts down in [close].
 */
class SessionJournal(
    private val root: File,
    private val exportDir: File,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val limitBytes: () -> Long,
    private val executor: JournalExecutor,
    private val writer: JournalFileWriter,
    private val summarizer: SessionSummarizer,
) : JournalSink, JournalReader, Closeable {

    private val writeLock = Any()
    private val router = SessionFileRouter()
    private val sessionsDir = File(root, "sessions")
    private val daysDir = File(root, "days")

    // Guarded by writeLock. Usually one; two while a closing session drains after the next one opened.
    private val openFiles = LinkedHashMap<OpenSession, File>()
    private var dropped = 0
    private var droppedFrom: Instant? = null
    private var droppedTo: Instant? = null

    // Every append() adds to this under writeLock; retain() resyncs it from disk after deleting, on the executor.
    private val usedBytes = AtomicLong(0)

    // Read by usage from any thread; written only by retain(), on the executor (the limit itself is read only there).
    @Volatile
    private var limit = 0L

    // The listener itself is invoked only from the executor, never under writeLock. Set by
    // JournalUsageSource; nothing else touches it.
    @Volatile
    private var usageListener: ((JournalUsage) -> Unit)? = null

    /** Always readable, from any thread: [usedBytes] is live (writers add to it, [retain] resyncs it), [limit] is set only by [retain]. */
    override val usage: JournalUsage get() = JournalUsage(usedBytes.get(), limit)

    /**
     * Replaces the single usage listener; `null` clears it. Called by [JournalUsageSource] from its
     * own collector; it is the listener it passes in, not this setter, that only ever runs from the
     * executor.
     */
    override fun setUsageListener(listener: ((JournalUsage) -> Unit)?) {
        usageListener = listener
    }

    // Taken before the first entry is accepted, so no file this process opens can be in it.
    init {
        val left = sessionLogs().filterNot { summaryFile(it).exists() }
        executor.execute {
            // Summarize everything first: a retain() between two of these could delete the next
            // one's .log before its turn, leaving an orphan .json behind.
            left.forEach(::writeSummaryFor)
            retain()
        }
    }

    override fun append(priority: Char, tag: String, message: String) {
        synchronized(writeLock) {
            // Android's clock reads microseconds; the file speaks milliseconds everywhere, and the
            // header prints this very instant as it is.
            val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
            val zone = zone()
            val entry = JournalEntry.at(now, zone, priority, tag, message)
            run(router.route(entry), now, zone)
        }
    }

    override fun close() = executor.shutdown()

    /** Holds [writeLock]. An entry any of whose writes failed counts once as dropped. */
    private fun run(actions: List<RouteAction>, now: Instant, zone: ZoneId) {
        var failed = false
        fun write(file: File, text: String) {
            if (!tryWrite(file, text, now, zone)) failed = true
        }
        actions.forEach { action ->
            when (action) {
                is RouteAction.Open -> {
                    val file = freeSessionFile(action.session.baseName)
                    openFiles[action.session] = file
                    write(file, openingText(action, zone))
                }
                is RouteAction.ToSession -> {
                    openFiles[action.session]?.let { write(it, action.entry.format()) }
                    if (Anchors.CLOSING.containsMatchIn(action.entry.firstLine)) scheduleTick()
                }
                is RouteAction.ToDay -> write(File(daysDir, "${action.day}.log"), action.entry.format())
                is RouteAction.Close -> {
                    val file = openFiles.remove(action.session)
                    if (file != null && action.kind == CloseKind.MISSING_CLOSED) {
                        val mark = JournalEntry.at(now, zone, 'W', Anchors.JOURNAL_TAG, Anchors.MISSING_CLOSED_MARK)
                        write(file, mark.format())
                    }
                    if (file != null) executor.execute { summarizeAndRetain(file) }
                }
            }
        }
        if (failed) {
            dropped++
            if (droppedFrom == null) droppedFrom = now
            droppedTo = now
        }
    }

    /** Holds [writeLock]. The first good write after a streak of failures reports it first, in the same file. */
    private fun tryWrite(file: File, text: String, now: Instant, zone: ZoneId): Boolean {
        val from = droppedFrom
        val to = droppedTo
        val full = if (dropped > 0 && from != null && to != null) {
            val notice = "Journal dropped $dropped entries between ${CLOCK.format(from.atZone(zone))} " +
                "and ${CLOCK.format(to.atZone(zone))}"
            JournalEntry.at(now, zone, 'W', "OpenSAB", notice).format() + text
        } else {
            text
        }
        return try {
            writer.append(file, full)
            dropped = 0
            droppedFrom = null
            droppedTo = null
            usedBytes.addAndGet(full.toByteArray(Charsets.UTF_8).size.toLong())
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun scheduleTick() {
        executor.schedule(MISSING_CLOSED_AFTER) {
            synchronized(writeLock) {
                val now = clock.instant()
                run(router.tick(now), now, zone())
            }
        }
    }

    private fun openingText(open: RouteAction.Open, zone: ZoneId): String {
        val at = open.session.opening.instant
        fun mark(message: String) = JournalEntry.at(at, zone, 'I', Anchors.JOURNAL_TAG, message).format()
        val offset = OFFSET.format(at.atZone(zone))
        return buildString {
            append(mark("${Anchors.HEADER_PREFIX}$at (zone ${zone.id}, offset $offset)"))
            append(mark(Anchors.PROLOGUE_MARK))
            open.prologue.forEach { append(it.format()) }
            append(mark(Anchors.SESSION_MARK))
        }
    }

    /** `<base>.log`, or `<base>-2.log`, `-3`… when the clock went back and the name is taken. */
    private fun freeSessionFile(baseName: String): File {
        var file = File(sessionsDir, "$baseName.log")
        var suffix = 2
        while (file.exists()) {
            file = File(sessionsDir, "$baseName-$suffix.log")
            suffix++
        }
        return file
    }

    /**
     * The open session first, without a summary, then the closed ones, newest opening first. A
     * closed session whose summary is missing, unreadable, failed or from an older summarizer is
     * summarized again and its `.json` rewritten.
     *
     * Always answers, exactly once: the caller may be suspended on it. A listing that throws answers
     * nothing listed; a closed journal answers the same at once, on the caller's thread.
     */
    override fun listSessions(callback: (List<SessionListing>) -> Unit) {
        val accepted = executor.execute { callback(listingOrNothing()) }
        if (!accepted) callback(emptyList())
    }

    /**
     * Fires once, between the listing's two snapshots, never under [writeLock]. Production leaves it
     * empty; tests use it to land a session's open exactly in that gap without a real race.
     */
    internal var listingSnapshotGap: () -> Unit = {}

    /** Executor only. */
    private fun listingOrNothing(): List<SessionListing> = try {
        val logs = sessionLogs()
        listingSnapshotGap()
        val open = synchronized(writeLock) { openFiles.values.toList() }
        val closed = logs.filter { it !in open }.map { log ->
            val summary = currentSummary(log)
            SessionListing(log.nameWithoutExtension, summary, isOpen = false, log.length() + summaryFile(log).length())
        }.sortedWith(compareBy(nullsLast(reverseOrder())) { it.summary?.openedAt?.let(Instant::parse) })
        val opened = open.reversed().map { SessionListing(it.nameWithoutExtension, null, isOpen = true, it.length()) }
        opened + closed
    } catch (e: Throwable) {
        emptyList()
    }

    /**
     * Purges stale zips, then packs the requested sessions' `.log`/`.json` (and every day file, if
     * asked) into a fresh zip under [exportDir]. Rejects the open session: its file is still being
     * written. Any throw along the way, including that rejection, surfaces as a failure. Always
     * answers, exactly once; a closed journal answers a failure at once, on the caller's thread.
     */
    override fun export(request: ExportRequest, callback: (Result<File>) -> Unit) {
        val accepted = executor.execute { callback(exportNow(request)) }
        if (!accepted) callback(Result.failure(IOException("The journal is closed")))
    }

    /** Executor only. */
    private fun exportNow(request: ExportRequest): Result<File> = try {
        val now = clock.instant()
        JournalExporter.purge(exportDir, now)
        val open = synchronized(writeLock) { openFiles.values.map { it.nameWithoutExtension } }
        open.firstOrNull { it in request.baseNames }?.let { throw IOException("Session $it is still open") }
        val files = request.baseNames.flatMap { base ->
            listOfNotNull(
                File(sessionsDir, "$base.log").takeIf { it.exists() },
                File(sessionsDir, "$base.json").takeIf { it.exists() },
            )
        } + if (request.includeDays) dayLogs() else emptyList()
        val zip = JournalExporter.freeZipFile(exportDir, now, zone())
        JournalExporter.zip(root, files, zip)
        Result.success(zip)
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /** Executor only. Retention follows the summary. */
    private fun summarizeAndRetain(log: File) {
        writeSummaryFor(log)
        retain()
    }

    /** Executor only. A no-op if [log] is already gone: retention can beat a queued summary to it. */
    private fun writeSummaryFor(log: File) {
        if (log.exists()) writeSummary(log, summarize(log))
    }

    /** Executor only: deletes what [JournalRetention] picks, recounts usage from disk and republishes it. */
    private fun retain() {
        val limitNow = limitBytes()
        limit = limitNow
        JournalRetention.plan(storedUnits(), limitNow, protectedIds())
            .forEach { unit -> unit.files.forEach { it.delete() } }
        deleteOrphanSummaries()
        usedBytes.set(diskUsage())
        usageListener?.invoke(JournalUsage(usedBytes.get(), limitNow))
    }

    /** A `.json` whose `.log` is already gone is dead weight forever otherwise: nothing ever counts it as a unit. */
    private fun deleteOrphanSummaries() {
        sessionsDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty().forEach { json ->
            if (!File(json.parentFile, "${json.nameWithoutExtension}.log").exists()) json.delete()
        }
    }

    private fun protectedIds(): Set<String> {
        val open = synchronized(writeLock) { openFiles.values.map { it.nameWithoutExtension } }
        val today = clock.instant().atZone(zone()).toLocalDate()
        return open.toSet() + "days/$today"
    }

    private fun storedUnits(): List<StoredUnit> {
        val sessions = sessionLogs().map { log ->
            val json = summaryFile(log)
            val files = listOfNotNull(log, json.takeIf { it.exists() })
            val sortKey = openedAtOf(json) ?: Instant.ofEpochMilli(log.lastModified())
            StoredUnit(log.nameWithoutExtension, sortKey, files.sumOf { it.length() }, files)
        }
        // A name that never parses would make every retain() throw forever; skip it instead.
        val days = dayLogs().mapNotNull { file ->
            val date = try {
                LocalDate.parse(file.nameWithoutExtension)
            } catch (e: DateTimeParseException) {
                return@mapNotNull null
            }
            StoredUnit("days/${file.nameWithoutExtension}", date.atStartOfDay(zone()).toInstant(), file.length(), listOf(file))
        }
        return sessions + days
    }

    private fun openedAtOf(json: File): Instant? = try {
        readSummary(json)?.openedAt?.let(Instant::parse)
    } catch (e: DateTimeParseException) {
        null
    }

    /** `null` for anything that is not a readable [SessionSummary]: missing, unreadable or malformed. */
    private fun readSummary(json: File): SessionSummary? = try {
        json.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.let(SessionSummaryCodec::decode)
    } catch (e: IOException) {
        null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun diskUsage(): Long =
        (sessionsDir.listFiles().orEmpty().asList() + daysDir.listFiles().orEmpty().asList())
            .filter { it.isFile }
            .sumOf { it.length() }

    private fun dayLogs(): List<File> =
        daysDir.listFiles { file -> file.isFile && file.name.endsWith(".log") }.orEmpty().toList()

    private fun currentSummary(log: File): SessionSummary {
        val stored = readSummary(summaryFile(log))
        if (stored != null && stored.failure == null && stored.summarizerVersion >= SUMMARIZER_VERSION) return stored
        return summarize(log).also { writeSummary(log, it) }
    }

    /** The summarizer should never throw, but a throw here must still leave a `.json` behind. */
    private fun summarize(log: File): SessionSummary {
        val baseName = log.nameWithoutExtension
        return try {
            summarizer.summarize(baseName, JournalEntryParser.parseSessionFile(log))
        } catch (e: RuntimeException) {
            failedSummary(baseName, e)
        } catch (e: IOException) {
            failedSummary(baseName, e)
        }
    }

    private fun failedSummary(baseName: String, e: Exception) = SessionSummary(
        summarizerVersion = SUMMARIZER_VERSION,
        baseName = baseName,
        attention = listOf(Attention.SUMMARY_FAILED),
        failure = SummaryFailure(entryIndex = -1, firstLine = "", exception = e.toString()),
    )

    /** Through a `.json.tmp` and a move, so a reader never sees half a summary. A failed write leaves none. */
    private fun writeSummary(log: File, summary: SessionSummary) {
        val target = summaryFile(log)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeText(SessionSummaryCodec.encode(summary), Charsets.UTF_8)
            try {
                Files.move(tmp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            tmp.delete()
        }
    }

    private fun sessionLogs(): List<File> =
        sessionsDir.listFiles { file -> file.isFile && file.name.endsWith(".log") }.orEmpty().toList()

    private fun summaryFile(log: File) = File(log.parentFile, "${log.nameWithoutExtension}.json")

    companion object {
        private val MISSING_CLOSED_AFTER: Duration = Duration.ofSeconds(10)
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
        private val OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("xxx", Locale.US)

        fun create(root: File, exportDir: File, limitBytes: () -> Long): SessionJournal = SessionJournal(
            root = root,
            exportDir = exportDir,
            clock = Clock.systemUTC(),
            zone = { ZoneId.systemDefault() },
            limitBytes = limitBytes,
            executor = JournalExecutor.singleThread(),
            writer = JournalFileWriter.APPENDING,
            summarizer = SessionSummarizer(),
        )
    }
}
