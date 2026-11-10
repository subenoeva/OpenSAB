package com.opensab.logging.journal

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** A session currently being written to its own file. */
data class OpenSession(val number: Int, val opening: JournalEntry) {
    val baseName: String get() = SessionFileNames.baseName(opening.local, number)
}

enum class CloseKind { CLOSED, MISSING_CLOSED, TRUNCATED }

sealed interface RouteAction {
    data class Open(val session: OpenSession, val prologue: List<JournalEntry>) : RouteAction
    data class ToSession(val session: OpenSession, val entry: JournalEntry) : RouteAction
    data class ToDay(val day: LocalDate, val entry: JournalEntry) : RouteAction
    data class Close(val session: OpenSession, val kind: CloseKind) : RouteAction
}

/**
 * Decides which file owns each entry. Pure: it touches no file.
 *
 * Owner: SessionJournal, which calls it only while holding its write lock. The prologue buffer
 * lives here and is therefore guarded by that same lock.
 */
class SessionFileRouter(
    private val prologueWindow: Duration = Duration.ofSeconds(30),
    private val missingClosedAfter: Duration = Duration.ofSeconds(10),
) {
    private val prologueBuffer = ArrayDeque<JournalEntry>()
    private var current: OpenSession? = null
    private var closingAt: Instant? = null

    // A session that was already closing when the next one opened: it still owns its own `closed in`.
    private var draining: OpenSession? = null
    private var drainingSince: Instant? = null

    val open: OpenSession? get() = current

    fun route(entry: JournalEntry): List<RouteAction> {
        bufferEntry(entry)
        val actions = mutableListOf<RouteAction>()
        closeIfOverdue(entry.instant, actions)

        val firstLine = entry.firstLine
        val opened = Anchors.OPENED.find(firstLine)
        val closedIn = Anchors.CLOSED_IN.find(firstLine)
        val closing = Anchors.CLOSING.find(firstLine)
        val existing = current
        val drained = draining

        when {
            drained != null && closedIn != null && closedIn.groupValues[1].toInt() == drained.number -> {
                actions += RouteAction.ToSession(drained, entry)
                actions += RouteAction.Close(drained, CloseKind.CLOSED)
                draining = null
                drainingSince = null
            }
            opened != null -> {
                val since = closingAt
                if (existing != null && since != null) {
                    drained?.let { actions += RouteAction.Close(it, CloseKind.MISSING_CLOSED) }
                    draining = existing
                    drainingSince = since
                } else {
                    existing?.let { actions += RouteAction.Close(it, CloseKind.MISSING_CLOSED) }
                }
                closingAt = null
                val session = OpenSession(opened.groupValues[1].toInt(), entry)
                actions += RouteAction.Open(session, prologueBuffer.filterNot { it === entry })
                current = session
                actions += RouteAction.ToSession(session, entry)
            }
            existing != null && closedIn != null && closedIn.groupValues[1].toInt() == existing.number -> {
                actions += RouteAction.ToSession(existing, entry)
                actions += RouteAction.Close(existing, CloseKind.CLOSED)
                current = null
                closingAt = null
            }
            existing != null && closing != null && closing.groupValues[1].toInt() == existing.number -> {
                closingAt = entry.instant
                actions += RouteAction.ToSession(existing, entry)
            }
            existing != null -> actions += RouteAction.ToSession(existing, entry)
            else -> actions += RouteAction.ToDay(entry.local.toLocalDate(), entry)
        }
        return actions
    }

    fun tick(now: Instant): List<RouteAction> {
        val actions = mutableListOf<RouteAction>()
        closeIfOverdue(now, actions)
        return actions
    }

    private fun closeIfOverdue(now: Instant, actions: MutableList<RouteAction>) {
        val drained = draining
        val drainedSince = drainingSince
        if (drained != null && drainedSince != null && Duration.between(drainedSince, now) >= missingClosedAfter) {
            actions += RouteAction.Close(drained, CloseKind.MISSING_CLOSED)
            draining = null
            drainingSince = null
        }
        val at = closingAt ?: return
        val session = current ?: return
        if (Duration.between(at, now) >= missingClosedAfter) {
            actions += RouteAction.Close(session, CloseKind.MISSING_CLOSED)
            current = null
            closingAt = null
        }
    }

    private fun bufferEntry(entry: JournalEntry) {
        prologueBuffer.addLast(entry)
        while (prologueBuffer.isNotEmpty() &&
            Duration.between(prologueBuffer.first().instant, entry.instant) > prologueWindow
        ) {
            prologueBuffer.removeFirst()
        }
    }
}
