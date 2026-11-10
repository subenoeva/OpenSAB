package com.opensab.logging.journal

import java.io.File

data class SessionListing(
    val baseName: String,
    /** `null` only for the open session, whose file is still being written. */
    val summary: SessionSummary?,
    val isOpen: Boolean,
    val sizeBytes: Long,
)

data class JournalUsage(val usedBytes: Long, val limitBytes: Long) {
    val overLimit: Boolean get() = usedBytes > limitBytes
}

data class ExportRequest(val baseNames: List<String>, val includeDays: Boolean)

/** What the UI may ask. Every callback runs on the journal's executor thread. */
interface JournalReader {
    val usage: JournalUsage
    fun setUsageListener(listener: ((JournalUsage) -> Unit)?)
    fun listSessions(callback: (List<SessionListing>) -> Unit)
    fun export(request: ExportRequest, callback: (Result<File>) -> Unit)
}

/** Release builds: nothing is recorded, so there is nothing to list. Callbacks run at once. */
object NoSessionJournal : JournalReader {
    override val usage: JournalUsage = JournalUsage(0, 0)

    override fun setUsageListener(listener: ((JournalUsage) -> Unit)?) = Unit

    override fun listSessions(callback: (List<SessionListing>) -> Unit) = callback(emptyList())

    override fun export(request: ExportRequest, callback: (Result<File>) -> Unit) =
        callback(Result.failure(IllegalStateException("No journal in this build")))
}
