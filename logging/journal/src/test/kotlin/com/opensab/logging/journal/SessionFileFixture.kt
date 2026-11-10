package com.opensab.logging.journal

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Replays a golden vector through [SessionFileRouter] and reconstructs, per session base name,
 * the exact sequence of entries the journal will write to that session's file: header, prologue
 * mark, prologue, session mark, the routed session entries and, when the router asks for it, the
 * missing-closed mark. This is the same sequence the journal itself will later write to disk.
 *
 * The header, prologue and session marks carry no timing information of their own in
 * [RouteAction]; like the journal, they are written with priority 'I', tag "Journal" and the
 * opening entry's instant. The missing-closed mark is written as the journal writes it: priority
 * 'W', tag "Journal", at the instant of the entry whose routing closed the session.
 */
object SessionFileFixture {

    private val OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("xxx", Locale.US)

    fun fromGolden(name: String): Map<String, List<JournalEntry>> {
        val router = SessionFileRouter()
        val entries = JournalEntryParser.parse(golden(name), MADRID)
        val files = LinkedHashMap<String, MutableList<JournalEntry>>()

        fun mark(session: OpenSession, message: String) =
            JournalEntry.at(session.opening.instant, MADRID, 'I', Anchors.JOURNAL_TAG, message)

        fun header(session: OpenSession): JournalEntry {
            val offset = OFFSET.format(session.opening.instant.atZone(MADRID))
            return JournalEntry.at(
                session.opening.instant, MADRID, 'I', Anchors.JOURNAL_TAG,
                "${Anchors.HEADER_PREFIX}${session.opening.instant} (zone ${MADRID.id}, offset $offset)",
            )
        }

        entries.forEach { entry ->
            router.route(entry).forEach { action ->
                when (action) {
                    is RouteAction.Open -> {
                        val file = files.getOrPut(action.session.baseName) { mutableListOf() }
                        file += header(action.session)
                        file += mark(action.session, Anchors.PROLOGUE_MARK)
                        file += action.prologue
                        file += mark(action.session, Anchors.SESSION_MARK)
                    }
                    is RouteAction.ToSession -> files.getValue(action.session.baseName) += action.entry
                    is RouteAction.Close -> if (action.kind == CloseKind.MISSING_CLOSED) {
                        files.getValue(action.session.baseName) +=
                            JournalEntry.at(entry.instant, MADRID, 'W', Anchors.JOURNAL_TAG, Anchors.MISSING_CLOSED_MARK)
                    }
                    is RouteAction.ToDay -> Unit
                }
            }
        }
        return files
    }
}
