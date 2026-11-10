package com.opensab.app.ui.sessions

import com.opensab.logging.journal.Attention
import com.opensab.logging.journal.SessionListing
import com.opensab.logging.journal.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SessionRowsTest {

    @Test
    fun unconnectedRunsFoldIntoOneAttemptsRow() {
        val rows = sessionRows(listOf(ride("c"), attempt("b2"), attempt("b1"), ride("a")), false, emptySet())
        assertEquals(listOf("Ride", "Attempts", "Ride"), rows.map { it::class.simpleName })
        assertEquals(2, (rows[1] as SessionRow.Attempts).listings.size)
    }

    @Test
    fun onlyAttentionKeepsRidesWithAttentionAndDropsAttempts() {
        val rows = sessionRows(
            listOf(ride("b", attention = listOf(Attention.NO_SAB_MODE)), attempt("x"), ride("a")),
            true,
            emptySet()
        )
        assertEquals(listOf("b"), rows.filterIsInstance<SessionRow.Ride>().map { it.listing.baseName })
        assertTrue(rows.none { it is SessionRow.Attempts })
    }

    @Test
    fun anAttemptsRowRemembersItIsExpanded() {
        val rows = sessionRows(listOf(attempt("b2"), attempt("b1")), false, setOf("b2"))
        assertTrue((rows.single() as SessionRow.Attempts).expanded)
    }

    @Test
    fun theOpenSessionIsARideEvenBeforeItStarts() {
        val open = SessionListing("o", summary = null, isOpen = true, sizeBytes = 0)
        assertTrue(sessionRows(listOf(open), false, emptySet()).single() is SessionRow.Ride)
    }

    /** An unconnected session that went wrong is not a quiet attempt: its ⚠ must stay visible. */
    @Test
    fun anUnconnectedSessionWithAttentionStaysARide() {
        val truncated = attempt("t", attention = listOf(Attention.TRUNCATED))
        assertTrue(sessionRows(listOf(truncated), true, emptySet()).single() is SessionRow.Ride)
        assertTrue(sessionRows(listOf(attempt("b"), truncated, attempt("a")), false, emptySet())[1] is SessionRow.Ride)
    }

    /** A failed summary cannot say whether the session connected, and its ⚠ must stay visible. */
    @Test
    fun aFailedSummaryIsARideNotAnAttempt() {
        val failed = attempt("f", attention = listOf(Attention.SUMMARY_FAILED))
            .let { it.copy(summary = it.summary!!.copy(failure = com.opensab.logging.journal.SummaryFailure(3, "x", "Boom"))) }
        assertTrue(sessionRows(listOf(failed), true, emptySet()).single() is SessionRow.Ride)
    }

    @Test
    fun theScreenLatencyHasOneDecimalInThePhoneLanguage() {
        assertEquals("0,3", screenLatencySeconds(312, Locale.forLanguageTag("es")))
        assertEquals("15.0", screenLatencySeconds(15_000, Locale.ENGLISH))
    }

    @Test
    fun theDetailListsEachRejectionWithItsErrorPositionAndEachException() {
        val summary = SessionSummary(
            summarizerVersion = 1,
            baseName = "s",
            rejections = listOf(com.opensab.logging.journal.Rejection(7, "INVALID_PARAMETER", "0x0012")),
            exceptionTypes = listOf("java.io.IOException"),
            notes = listOf("CUE-UNHEARD"),
        )
        val fields = summaryFields(summary)
        assertTrue(fields.any { (name, value) -> name.startsWith("rejection") && value == "#7 INVALID_PARAMETER @0x0012" })
        assertTrue(fields.any { (_, value) -> value == "java.io.IOException" })
        assertTrue(fields.any { (_, value) -> value == "CUE-UNHEARD" })
        assertEquals(fields.size, fields.map { it.first }.toSet().size)
    }

    private fun ride(name: String, attention: List<Attention> = emptyList()) =
        listing(name, connected = true, attention = attention)

    private fun attempt(name: String, attention: List<Attention> = emptyList()) =
        listing(name, connected = false, attention = attention)

    private fun listing(name: String, connected: Boolean, attention: List<Attention>) = SessionListing(
        baseName = name,
        summary = SessionSummary(summarizerVersion = 1, baseName = name, connected = connected, attention = attention),
        isOpen = false,
        sizeBytes = 100,
    )
}
