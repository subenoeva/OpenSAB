package com.opensab.logging.journal

import java.time.Duration
import java.time.Instant

private val HEADER_ZONE = Regex("""\(zone ([^,]+), offset""")

/** Parses a "Link cue ... played on ..." line, falling back to the route-only shape when the rest does not match. */
private fun parseLinkCue(line: String): LinkCue? {
    Anchors.LINK_CUE_FULL.matchEntire(line)?.let {
        val g = it.groupValues
        return LinkCue(g[1], g[2], g[3], g[4].toInt(), g[5].toInt(), g[6].toBoolean(), g[7])
    }
    Anchors.LINK_CUE_ROUTE_ONLY.matchEntire(line)?.let {
        return LinkCue(it.groupValues[1], it.groupValues[2])
    }
    return null
}

/** [KnownDefects.CUE_UNHEARD]: a route the rider is unlikely to hear, muted, or at zero volume. */
private fun isCueUnheard(cue: LinkCue): Boolean =
    cue.route.startsWith("speaker") || cue.muted == true || cue.volume == 0

/**
 * Turns one session file's entries into its [SessionSummary]. The prologue (everything up to and
 * including [Anchors.SESSION_MARK]) counts for nothing: only the session itself is summarized.
 */
open class SessionSummarizer {

    /** Never throws: a failure is returned as a summary with [SessionSummary.failure] and SUMMARY_FAILED. */
    open fun summarize(baseName: String, entries: List<JournalEntry>): SessionSummary {
        var current = -1
        try {
            val header = entries.firstOrNull { it.message.startsWith(Anchors.HEADER_PREFIX) }
            val openedAt = header?.instant
            val zone = header?.let { HEADER_ZONE.find(it.message)?.groupValues?.get(1) }

            val sessionStart = (entries.indexOfFirst { it.message == Anchors.SESSION_MARK } + 1)
                .coerceAtLeast(0)

            var sessionNumber: Int? = null
            var origin: String? = null
            var connected = false
            var closeKind = CloseKind.TRUNCATED
            var closeReason: String? = null
            var closeActions: Int? = null
            var closedAt: Instant? = null
            var closedInMs: Long? = null
            var missingClosedMarked = false
            var sabRequestAt: Instant? = null
            var sabGrantedAt: Instant? = null
            var sabModeNotGrantedWarnings = 0
            var projectionFrames = 0
            val rejections = mutableListOf<Rejection>()

            var warnings = 0
            var errors = 0
            val exceptionTypes = mutableListOf<String>()
            var unknownException = false
            var calls = 0
            var callsGone = 0
            var messages = 0
            var navigationManeuvers = 0
            var weatherUpdates = 0
            var weatherRestored = 0
            var indicatorChanges = 0
            var handlebarPresses = 0
            var mirrorPresses = 0
            val linkCues = mutableListOf<LinkCue>()
            var linkCuesSkipped = 0
            var droppedEntries = 0
            var cueUnheardNote = false

            for (i in sessionStart until entries.size) {
                current = i
                val entry = entries[i]
                val line = entry.firstLine

                Anchors.OPENED.find(line)?.let {
                    sessionNumber = it.groupValues[1].toInt()
                    origin = it.groupValues[3]
                }
                if (Anchors.STARTED.find(line) != null) connected = true
                Anchors.CLOSING.find(line)?.let {
                    closeReason = it.groupValues[2]
                    closeActions = it.groupValues[3].toInt()
                }
                Anchors.CLOSED_IN.find(line)?.let {
                    closeKind = CloseKind.CLOSED
                    closedInMs = it.groupValues[2].toLong()
                    closedAt = entry.instant
                }
                if (entry.message == Anchors.MISSING_CLOSED_MARK) missingClosedMarked = true

                if (sabRequestAt == null && line.startsWith(Anchors.SAB_REQUEST)) {
                    sabRequestAt = entry.instant
                } else if (sabRequestAt != null && sabGrantedAt == null && line == Anchors.SAB_GRANTED) {
                    sabGrantedAt = entry.instant
                }
                if (line.startsWith(Anchors.SAB_NOT_GRANTED)) sabModeNotGrantedWarnings++
                if (line.startsWith(Anchors.FRAME_ACK)) projectionFrames++
                Anchors.REJECTED.find(line)?.let {
                    rejections += Rejection(
                        requestId = it.groupValues[2].toInt(),
                        status = it.groupValues[3],
                        errorPosition = it.groupValues[4],
                    )
                }

                if (entry.tag != Anchors.JOURNAL_TAG) {
                    when (entry.priority) {
                        'W' -> warnings++
                        'E' -> errors++
                    }
                }

                if (line.startsWith(Anchors.CALL_FROM)) calls++
                if (Anchors.CALL_GONE.matches(line)) callsGone++
                if (line.startsWith(Anchors.MESSAGE_FROM)) messages++
                if (line.startsWith(Anchors.NAV_MANEUVER)) navigationManeuvers++
                if (line.startsWith(Anchors.WEATHER_UPDATED)) weatherUpdates++
                if (line == Anchors.WEATHER_RESTORED) weatherRestored++
                if (line.startsWith(Anchors.INDICATORS_CHANGED)) indicatorChanges++
                if (Anchors.HANDLEBAR_PRESS.matches(line)) handlebarPresses++
                if (line.startsWith(Anchors.MIRROR_PRESS)) mirrorPresses++

                val cue = parseLinkCue(line)
                if (cue != null) {
                    linkCues += cue
                    if (cue.cue == "LINK_UP" && isCueUnheard(cue)) cueUnheardNote = true
                } else if (Anchors.LINK_CUE_SKIPPED.find(line) != null) {
                    linkCuesSkipped++
                }

                Anchors.DROPPED_ENTRIES.find(line)?.let { droppedEntries += it.groupValues[1].toInt() }

                val isNoteEntry = line.contains(Anchors.FGS_FAILURE) || (cue != null && cue.cue == "LINK_UP" && isCueUnheard(cue))
                val isSubscriptionEnd = line in Anchors.SUBSCRIPTION_ENDED
                entry.message.split('\n').drop(1).forEach { continuation ->
                    Anchors.EXCEPTION_TYPE.find(continuation)?.groupValues?.get(1)?.let { type ->
                        if (isSubscriptionEnd && type.endsWith("CancellationException")) return@let
                        if (type !in exceptionTypes) exceptionTypes += type
                        if (!isNoteEntry) unknownException = true
                    }
                }
            }

            if (closeKind != CloseKind.CLOSED && missingClosedMarked) closeKind = CloseKind.MISSING_CLOSED

            val fgsNote = entries.any { it.firstLine.contains(Anchors.FGS_FAILURE) }
            val notes = buildList {
                if (cueUnheardNote) add(KnownDefects.CUE_UNHEARD)
                if (fgsNote) add(KnownDefects.FGS_NO_MIC)
            }

            val lastInstant = if (entries.size > sessionStart) entries.last().instant else null
            val durationMs = openedAt?.let { start ->
                (closedAt ?: lastInstant)?.let { end -> Duration.between(start, end).toMillis() }
            }

            val attention = mutableListOf<Attention>()
            if (connected && sabGrantedAt == null) attention += Attention.NO_SAB_MODE
            if (rejections.isNotEmpty()) attention += Attention.REJECTED_FRAME
            when (closeKind) {
                CloseKind.MISSING_CLOSED -> attention += Attention.MISSING_CLOSED
                CloseKind.TRUNCATED -> attention += Attention.TRUNCATED
                CloseKind.CLOSED -> Unit
            }
            if (unknownException) attention += Attention.UNKNOWN_EXCEPTION
            if (droppedEntries > 0) attention += Attention.DROPPED_ENTRIES

            return SessionSummary(
                summarizerVersion = SUMMARIZER_VERSION,
                baseName = baseName,
                sessionNumber = sessionNumber,
                openedAt = openedAt?.toString(),
                zone = zone,
                closedAt = closedAt?.toString(),
                durationMs = durationMs,
                origin = origin,
                closeKind = closeKind,
                closeReason = closeReason,
                closeActions = closeActions,
                closedInMs = closedInMs,
                connected = connected,
                sabModeGranted = sabGrantedAt != null,
                sabModeLatencyMs = if (sabRequestAt != null && sabGrantedAt != null) {
                    Duration.between(sabRequestAt, sabGrantedAt).toMillis()
                } else null,
                sabModeNotGrantedWarnings = sabModeNotGrantedWarnings,
                projectionFrames = projectionFrames,
                rejections = rejections,
                warnings = warnings,
                errors = errors,
                exceptionTypes = exceptionTypes,
                calls = calls,
                callsGone = callsGone,
                messages = messages,
                navigationManeuvers = navigationManeuvers,
                weatherUpdates = weatherUpdates,
                weatherRestored = weatherRestored,
                indicatorChanges = indicatorChanges,
                handlebarPresses = handlebarPresses,
                mirrorPresses = mirrorPresses,
                linkCues = linkCues,
                linkCuesSkipped = linkCuesSkipped,
                droppedEntries = droppedEntries,
                attention = attention,
                notes = notes,
            )
        } catch (e: RuntimeException) {
            val failedEntry = entries.getOrNull(current)
            return SessionSummary(
                summarizerVersion = SUMMARIZER_VERSION,
                baseName = baseName,
                failure = SummaryFailure(current, failedEntry?.firstLine.orEmpty(), e.toString()),
                attention = listOf(Attention.SUMMARY_FAILED),
            )
        }
    }
}
