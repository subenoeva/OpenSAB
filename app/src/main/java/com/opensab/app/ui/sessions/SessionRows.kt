package com.opensab.app.ui.sessions

import com.opensab.logging.journal.SessionListing
import com.opensab.logging.journal.SessionSummary
import java.util.Locale

sealed interface SessionRow {
    data class Ride(val listing: SessionListing) : SessionRow

    /** Consecutive sessions that never started: not rides. Collapsed unless expanded. */
    data class Attempts(val listings: List<SessionListing>, val expanded: Boolean) : SessionRow {
        val key: String get() = listings.first().baseName
    }
}

/**
 * An attempt is a closed session that never started and has nothing to look at. One with a ⚠
 * (truncated, a failed summary, an unknown exception...) stays a ride, or folding it would hide it;
 * so does the open session, which has no summary yet.
 */
val SessionListing.isAttempt: Boolean
    get() = summary?.let { !it.connected && it.attention.isEmpty() } ?: false

/** Newest first; runs of unconnected sessions become one Attempts row; onlyAttention keeps rides with ⚠. */
fun sessionRows(listings: List<SessionListing>, onlyAttention: Boolean, expanded: Set<String>): List<SessionRow> {
    if (onlyAttention) {
        return listings.filter { !it.isAttempt && !it.summary?.attention.isNullOrEmpty() }.map { SessionRow.Ride(it) }
    }
    val rows = mutableListOf<SessionRow>()
    val run = mutableListOf<SessionListing>()
    fun closeRun() {
        if (run.isEmpty()) return
        rows += SessionRow.Attempts(run.toList(), expanded = run.first().baseName in expanded)
        run.clear()
    }
    for (listing in listings) {
        if (listing.isAttempt) {
            run += listing
        } else {
            closeRun()
            rows += SessionRow.Ride(listing)
        }
    }
    closeRun()
    return rows
}

/**
 * Every field of [summary] as a name and a value, for the detail. The names are the summary's own
 * identifiers, the same ones the exported `.json` carries. Unique names, so they can key a list.
 */
fun summaryFields(summary: SessionSummary): List<Pair<String, String>> = buildList {
    fun field(name: String, value: Any?) {
        if (value != null) add(name to value.toString())
    }
    field("summarizerVersion", summary.summarizerVersion)
    field("sessionNumber", summary.sessionNumber)
    field("openedAt", summary.openedAt)
    field("zone", summary.zone)
    field("closedAt", summary.closedAt)
    field("durationMs", summary.durationMs)
    field("origin", summary.origin)
    field("closeKind", summary.closeKind)
    field("closeReason", summary.closeReason)
    field("closeActions", summary.closeActions)
    field("closedInMs", summary.closedInMs)
    field("connected", summary.connected)
    field("sabModeGranted", summary.sabModeGranted)
    field("sabModeLatencyMs", summary.sabModeLatencyMs)
    field("sabModeNotGrantedWarnings", summary.sabModeNotGrantedWarnings)
    field("projectionFrames", summary.projectionFrames)
    field("warnings", summary.warnings)
    field("errors", summary.errors)
    field("calls", summary.calls)
    field("callsGone", summary.callsGone)
    field("messages", summary.messages)
    field("navigationManeuvers", summary.navigationManeuvers)
    field("weatherUpdates", summary.weatherUpdates)
    field("weatherRestored", summary.weatherRestored)
    field("indicatorChanges", summary.indicatorChanges)
    field("handlebarPresses", summary.handlebarPresses)
    field("mirrorPresses", summary.mirrorPresses)
    field("linkCuesSkipped", summary.linkCuesSkipped)
    field("droppedEntries", summary.droppedEntries)
    summary.attention.forEachIndexed { i, it -> field("attention[$i]", it) }
    summary.rejections.forEachIndexed { i, it ->
        field("rejection[$i]", "#${it.requestId} ${it.status} @${it.errorPosition}")
    }
    summary.exceptionTypes.forEachIndexed { i, it -> field("exception[$i]", it) }
    summary.linkCues.forEachIndexed { i, it ->
        field("linkCue[$i]", listOfNotNull(it.cue, it.route, it.channel).joinToString(" · "))
        field("linkCue[$i].volume", it.volume?.let { v -> "$v/${it.maxVolume}" })
        field("linkCue[$i].muted", it.muted)
        field("linkCue[$i].interruptions", it.interruptions)
    }
    summary.notes.forEachIndexed { i, it -> field("note[$i]", it) }
    summary.failure?.let {
        field("failure", "[${it.entryIndex}] ${it.exception} — ${it.firstLine}")
    }
}

/** Seconds with one decimal, in the phone's language: `0,3` in Spanish. */
fun screenLatencySeconds(latencyMs: Long, locale: Locale): String =
    String.format(locale, "%.1f", latencyMs / 1000.0)
