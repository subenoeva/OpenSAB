package com.opensab.logging.journal

import com.opensab.logging.journal.Anchors.MISSING_CLOSED_MARK
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSummarizerTest {

    private val files = SessionFileFixture.fromGolden("daily-use.log")
    private fun summary(baseName: String) = SessionSummarizer().summarize(baseName, files.getValue(baseName))

    @Test
    fun theDailyUseGoldenGivesTheFramesAndScreenLatencyOfEachSession() {
        val expected = mapOf(
            "2026-09-23_19-41-33_s1" to Triple("SCANNER", 97, 402L),
            "2026-09-23_20-56-18_s2" to Triple("RECEIVER", 25, 183L),
            "2026-09-23_21-17-22_s3" to Triple("RECEIVER", 56, 15_149L),
            "2026-09-24_20-24-13_s1" to Triple("RECEIVER", 21, 385L),
            "2026-09-24_21-31-47_s2" to Triple("RECEIVER", 0, null),
            "2026-09-24_21-32-20_s3" to Triple("RECEIVER", 10, 376L),
        )
        expected.forEach { (name, e) ->
            val s = summary(name)
            assertEquals(name, e.first, s.origin)
            assertEquals(name, e.second, s.projectionFrames)
            assertEquals(name, e.third, s.sabModeLatencyMs)
            assertEquals(name, CloseKind.CLOSED, s.closeKind)
            assertEquals(name, "LINK_LOST", s.closeReason)
            assertTrue(name, s.connected)
            assertEquals(name, emptyList<Rejection>(), s.rejections)
        }
    }

    @Test
    fun theClosedInTimeAndDurationComeFromTheSession() {
        val s = summary("2026-09-23_19-41-33_s1")
        assertEquals(67L, s.closedInMs)
        assertEquals(477_182L, s.durationMs)          // 19:41:33.342 -> 19:49:30.524
        assertEquals("2026-09-23T17:41:33.342Z", s.openedAt)
        assertEquals(17, s.closeActions)
        assertEquals(1, s.sessionNumber)
    }

    @Test
    fun aSessionThatNeverGotTheScreenIsFlagged() {
        val s = summary("2026-09-24_21-31-47_s2")
        assertFalse(s.sabModeGranted)
        assertEquals(1, s.sabModeNotGrantedWarnings)
        assertTrue(Attention.NO_SAB_MODE in s.attention)
    }

    @Test
    fun keepalivesAndTheScreenRequestAreNotFrames() {
        val s = SessionSummarizer().summarize("x", session(
            "BTU acknowledged frame successfully: commandId=0x03, requestId=4",
            "BTU acknowledged frame successfully: commandId=0x0A, requestId=5",
            "BTU acknowledged frame successfully: commandId=0x01, requestId=6",
        ))
        assertEquals(1, s.projectionFrames)
    }

    @Test
    fun everyRejectionIsKeptWithItsErrorPosition() {
        val s = SessionSummarizer().summarize("x", session(*golden("rejections.log")
            .map { it.substringAfter("] ") }.toList().toTypedArray()))
        assertEquals(listOf("21", "21", "21", "21", "22"), s.rejections.map { it.errorPosition })
        assertEquals("0x0C", s.rejections.first().status)
        assertTrue(Attention.REJECTED_FRAME in s.attention)
    }

    @Test
    fun aSessionWithoutStartedIsAnAttemptAndNotFlaggedForTheScreen() {
        val s = SessionSummarizer().summarize("x", session(started = false))
        assertFalse(s.connected)
        assertFalse(Attention.NO_SAB_MODE in s.attention)
    }

    @Test
    fun aFileWithoutClosedInIsTruncatedAndOneWithTheMarkIsMissingClosed() {
        assertEquals(CloseKind.TRUNCATED, SessionSummarizer().summarize("x", session(closed = false)).closeKind)
        val marked = session(closed = false) + journal('W', MISSING_CLOSED_MARK)
        val s = SessionSummarizer().summarize("x", marked)
        assertEquals(CloseKind.MISSING_CLOSED, s.closeKind)
        assertTrue(Attention.MISSING_CLOSED in s.attention)
    }

    @Test
    fun thePrologueCountsForNothing() {
        val s = SessionSummarizer().summarize("x",
            session(prologue = listOf("BTU acknowledged frame successfully: commandId=0x01, requestId=1",
                                      "DashboardMode changed to: SAB_MODE")))
        assertEquals(0, s.projectionFrames)
    }

    @Test
    fun theDailyUseGoldenGetsItsNotesAndOnlyTheScreenlessSessionIsFlagged() {
        val expected = mapOf(
            "2026-09-23_19-41-33_s1" to (emptyList<String>() to emptyList()),
            "2026-09-23_20-56-18_s2" to (listOf("CUE-UNHEARD", "FGS-NO-MIC") to emptyList()),
            "2026-09-23_21-17-22_s3" to (listOf("CUE-UNHEARD", "FGS-NO-MIC") to emptyList()),
            "2026-09-24_20-24-13_s1" to (listOf("CUE-UNHEARD", "FGS-NO-MIC") to emptyList()),
            "2026-09-24_21-31-47_s2" to (listOf("FGS-NO-MIC") to listOf(Attention.NO_SAB_MODE)),
            "2026-09-24_21-32-20_s3" to (listOf("FGS-NO-MIC") to emptyList()),
        )
        expected.forEach { (name, e) ->
            val s = summary(name)
            assertEquals(name, e.first, s.notes)
            assertEquals(name, e.second, s.attention)
        }
    }

    @Test
    fun theSpeakerSafeCueIsParsedWithItsVolume() {
        val cue = summary("2026-09-24_20-24-13_s1").linkCues.first { it.cue == "LINK_UP" }
        assertEquals(LinkCue("LINK_UP", "speaker-safe Pixel 7", "NOTIFICATION", 0, 7, true, "PRIORITY"), cue)
    }

    @Test
    fun aRouteWithAPlusAndTheDefaultTextsAreParsed() {
        // Format of GatedCuePlayer + AndroidCuePlayer.describe(): "Link cue %s played on %s"
        val s = SessionSummarizer().summarize("x", session(
            "Link cue LINK_UP played on bt-sco Casco + speaker Pixel 7 on the MEDIA channel (volume 5/25, muted=false, interruptions=ALL)",
            "Link cue LINK_DOWN played on an unknown output",
            "Link cue LINK_UP skipped: sound cues are off"))
        assertEquals("bt-sco Casco + speaker Pixel 7", s.linkCues[0].route)
        assertEquals(LinkCue("LINK_DOWN", "an unknown output"), s.linkCues[1])
        assertEquals(1, s.linkCuesSkipped)
    }

    @Test
    fun aLinkUpInThePrologueIsNotThisSessionsCue() {
        val s = SessionSummarizer().summarize("x", session(prologue = listOf(
            "Link cue LINK_UP played on speaker-safe Pixel 7 on the NOTIFICATION channel (volume 0/7, muted=true, interruptions=ALL)")))
        assertFalse("CUE-UNHEARD" in s.notes)
    }

    @Test
    fun sourcesAreCountedFromTheirLiteralFormats() {
        val s = SessionSummarizer().summarize("x", session(
            "Call from REDACTED (RINGING)",                    // CallManager "Call from %s (%s)"
            "Call 0|com.google.android.dialer|1|null|10120 is gone", // "Call %s is gone"
            "Message from REDACTED on WhatsApp",               // "Message from %s on %s"
            "Parsed navigation maneuver from com.google.android.apps.maps: NavInstruction(title=REDACTED)",
            "Weather updated: 25°C CLEAR",
            "Restored the last weather reading from cache",
            "Active indicators changed to 0x02 (CALL)",
            "Handlebar press SELECT",
            "Handlebar press BACK",
            "Injecting mirror press SELECT"))
        assertEquals(1, s.calls); assertEquals(1, s.callsGone); assertEquals(1, s.messages)
        assertEquals(1, s.navigationManeuvers); assertEquals(1, s.weatherUpdates); assertEquals(1, s.weatherRestored)
        assertEquals(1, s.indicatorChanges); assertEquals(2, s.handlebarPresses); assertEquals(1, s.mirrorPresses)
    }

    @Test
    fun anUnknownTopLevelExceptionFlagsTheSessionButCausedByDoesNot() {
        val s = SessionSummarizer().summarize("x", sessionWithEntries(
            warn("Weather fetch failed\njava.io.IOException: timeout\n\tat a.B.c(B.kt:1)\nCaused by: java.net.SocketTimeoutException: x")))
        assertEquals(listOf("java.io.IOException"), s.exceptionTypes)
        assertTrue(Attention.UNKNOWN_EXCEPTION in s.attention)
        assertEquals(1, s.warnings)
    }

    @Test
    fun cancellationAndTheKnownFgsFailureDoNotFlag() {
        val s = SessionSummarizer().summarize("x", sessionWithEntries(
            warn("AssignmentControl notifications subscription terminated with error\nkotlinx.coroutines.JobCancellationException: Job was cancelled"),
            warn("Failed to start foreground service with type 152, falling back to CONNECTED_DEVICE\njava.lang.SecurityException: Starting FGS with type microphone")))
        assertEquals(emptyList<Attention>(), s.attention)
        assertEquals(listOf("FGS-NO-MIC"), s.notes)
    }

    /**
     * Sessions recorded before the subscriptions stopped warning about their own cancellation
     * (2026-09-24 to 2026-10-11) carry these three lines in every file. They were never trouble.
     */
    @Test
    fun theSubscriptionCancellationsOfOlderSessionsAreNeitherListedNorFlagged() {
        val s = SessionSummarizer().summarize("x", sessionWithEntries(
            warn("AssignmentControl notifications subscription terminated with error\nkotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled"),
            warn("FunctionNotification observation failed\nkotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled"),
            warn("ControlPoint notifications subscription terminated with error\nkotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled")))
        assertEquals(emptyList<String>(), s.exceptionTypes)
        assertEquals(emptyList<Attention>(), s.attention)
    }

    @Test
    fun aSubscriptionTheLinkKilledIsListedAndFlagged() {
        val s = SessionSummarizer().summarize("x", sessionWithEntries(
            warn("ControlPoint notifications subscription terminated with error\ncom.opensab.sab.session.ble.BleDisconnectedException: link lost")))
        assertEquals(listOf("com.opensab.sab.session.ble.BleDisconnectedException"), s.exceptionTypes)
        assertTrue(Attention.UNKNOWN_EXCEPTION in s.attention)
    }

    /**
     * 2026-10-07 21:10:46: the link was already dead and the AssignmentControl subscription never
     * armed. A timeout is a `CancellationException` by type and trouble all the same.
     */
    @Test
    fun aCancellationOutsideThoseThreeLinesIsTroubleLikeAnyOther() {
        val s = SessionSummarizer().summarize("x", sessionWithEntries(
            warn("AssignmentControl subscription did not arm; running the assignment blind (results will all read as timed out)\nkotlinx.coroutines.TimeoutCancellationException: Timed out waiting for 2000 ms")))
        assertEquals(listOf("kotlinx.coroutines.TimeoutCancellationException"), s.exceptionTypes)
        assertTrue(Attention.UNKNOWN_EXCEPTION in s.attention)
    }

    @Test
    fun aSubscriptionCancelledSinceTheFixLeavesNoTrace() {
        val s = SessionSummarizer().summarize("x", sessionWithEntries(
            logEntry(LINES_START, 'D', "AssignmentControl notifications subscription cancelled"),
            logEntry(LINES_START, 'D', "FunctionNotification observation cancelled"),
            logEntry(LINES_START, 'D', "ControlPoint notifications subscription cancelled")))
        assertEquals(0, s.warnings)
        assertEquals(emptyList<String>(), s.exceptionTypes)
        assertEquals(emptyList<Attention>(), s.attention)
    }

    @Test
    fun droppedEntriesAreCountedAndFlagged() {
        val s = SessionSummarizer().summarize("x", sessionWithEntries(
            warn("Journal dropped 12 entries between 20:00:01 and 20:00:04")))
        assertEquals(12, s.droppedEntries)
        assertTrue(Attention.DROPPED_ENTRIES in s.attention)
    }

    @Test
    fun aSummaryRoundTripsThroughJson() {
        val s = summary("2026-09-24_21-31-47_s2")
        assertEquals(s, SessionSummaryCodec.decode(SessionSummaryCodec.encode(s)))
    }

    // ---- test fixtures -------------------------------------------------------------------

    private val OPENED_AT: LocalDateTime = LocalDateTime.of(2026, 9, 24, 19, 59, 58)
    private val STARTED_AT: LocalDateTime = LocalDateTime.of(2026, 9, 24, 19, 59, 59)
    private val LINES_START: LocalDateTime = LocalDateTime.of(2026, 9, 24, 20, 0, 0)

    private fun logEntry(local: LocalDateTime, priority: Char, message: String): JournalEntry =
        JournalEntry.at(local.atZone(MADRID).toInstant(), MADRID, priority, "OpenSAB", message)

    /** An entry with tag "Journal", the shape the journal itself writes its own marks with. */
    private fun journal(priority: Char, message: String, at: LocalDateTime = LINES_START.plusSeconds(10)): JournalEntry =
        JournalEntry.at(at.atZone(MADRID).toInstant(), MADRID, priority, Anchors.JOURNAL_TAG, message)

    /** A warning entry, tag "OpenSAB" like a real product log line, at [LINES_START]. */
    private fun warn(message: String): JournalEntry = logEntry(LINES_START, 'W', message)

    /**
     * A whole session file's entries: header, marks, the opening and start of session #1
     * (origin=RECEIVER), the given [sessionEntries] and, if [closed], the closing and closed-in
     * lines. Shared by [session] (built from plain strings) and [sessionWithEntries] (entries
     * already made, for messages with continuation lines).
     */
    private fun sessionCore(
        sessionEntries: List<JournalEntry>,
        started: Boolean,
        closed: Boolean,
        prologue: List<String>,
    ): List<JournalEntry> {
        val opening = logEntry(OPENED_AT, 'I', "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")
        val header = JournalEntry.at(
            opening.instant, MADRID, 'I', Anchors.JOURNAL_TAG,
            "${Anchors.HEADER_PREFIX}${opening.instant} (zone Europe/Madrid, offset +02:00)",
        )
        val prologueEntries = prologue.mapIndexed { i, msg ->
            logEntry(OPENED_AT.minusSeconds((prologue.size - i).toLong()), 'D', msg)
        }

        val result = mutableListOf<JournalEntry>()
        result += header
        result += journal('I', Anchors.PROLOGUE_MARK, OPENED_AT)
        result += prologueEntries
        result += journal('I', Anchors.SESSION_MARK, OPENED_AT)
        result += opening
        if (started) result += logEntry(STARTED_AT, 'I', "SabSession #1 started 8 participants: engine")
        result += sessionEntries
        if (closed) {
            val closingAt = LINES_START.plusSeconds(5)
            result += logEntry(closingAt, 'I', "SabSession #1 closing (LINK_LOST), 17 close actions")
            result += logEntry(closingAt.plusNanos(69_000_000L), 'I', "SabSession #1 closed in 69 ms")
        }
        return result
    }

    /**
     * A whole session file's entries: header, marks, the opening and start of session #1
     * (origin=RECEIVER), the given [lines] (one entry each, one millisecond apart, starting at
     * 2026-09-24 20:00:00.000) and, if [closed], the closing and closed-in lines.
     */
    private fun session(
        vararg lines: String,
        started: Boolean = true,
        closed: Boolean = true,
        prologue: List<String> = emptyList(),
    ): List<JournalEntry> = sessionCore(
        lines.mapIndexed { i, msg -> logEntry(LINES_START.plusNanos(i * 1_000_000L), 'I', msg) },
        started, closed, prologue,
    )

    /**
     * Like [session], but with entries already made (for messages with continuation lines).
     * Defaults to [started] `false`: these tests are about warnings, exceptions and notes, not
     * about the screen handshake, so the session stays a bare attempt unless said otherwise.
     */
    private fun sessionWithEntries(
        vararg entries: JournalEntry,
        started: Boolean = false,
        closed: Boolean = true,
        prologue: List<String> = emptyList(),
    ): List<JournalEntry> = sessionCore(entries.toList(), started, closed, prologue)
}
