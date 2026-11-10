package com.opensab.app.ui.sessions

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.opensab.logging.journal.Attention
import com.opensab.logging.journal.CloseKind
import com.opensab.logging.journal.ExportRequest
import com.opensab.logging.journal.Rejection
import com.opensab.logging.journal.SessionListing
import com.opensab.logging.journal.SessionSummary

class SessionsScreenPreviewProvider : PreviewParameterProvider<SessionsUiState> {

    private val open = SessionListing("2026-09-25_18-02-11_s3", summary = null, isOpen = true, sizeBytes = 48_000)

    private val clean = listing(
        "2026-09-25_09-14-02_s2",
        SessionSummary(
            summarizerVersion = 1,
            baseName = "2026-09-25_09-14-02_s2",
            sessionNumber = 2,
            openedAt = "2026-09-25T07:14:02Z",
            durationMs = 2_460_000,
            origin = "RECEIVER",
            closeKind = CloseKind.CLOSED,
            connected = true,
            sabModeGranted = true,
            sabModeLatencyMs = 312,
            projectionFrames = 209,
            calls = 2,
            notes = listOf("CUE-UNHEARD"),
        )
    )

    private val attempt = listing(
        "2026-09-24_21-30-00_s1",
        SessionSummary(
            summarizerVersion = 1,
            baseName = "2026-09-24_21-30-00_s1",
            openedAt = "2026-09-24T19:30:00Z",
            closeKind = CloseKind.CLOSED,
            closeReason = "link lost",
        )
    )

    private val flagged = listing(
        "2026-09-24_18-40-00_s1",
        SessionSummary(
            summarizerVersion = 1,
            baseName = "2026-09-24_18-40-00_s1",
            openedAt = "2026-09-24T16:40:00Z",
            durationMs = 780_000,
            origin = "SCANNER",
            connected = true,
            projectionFrames = 12,
            rejections = listOf(Rejection(7, "INVALID_PARAMETER", "0x0012")),
            attention = listOf(Attention.NO_SAB_MODE, Attention.REJECTED_FRAME),
        )
    )

    private val listings = listOf(open, clean, attempt, flagged)

    override val values: Sequence<SessionsUiState> = sequenceOf(
        // Scenario 1: Loading
        SessionsUiState(),
        // Scenario 2: Nothing recorded yet
        SessionsUiState(isLoading = false),
        // Scenario 3: The list, with the open session, a clean ride, an attempt and a flagged ride
        SessionsUiState(isLoading = false, listings = listings),
        // Scenario 4: Two rides chosen by long press
        SessionsUiState(isLoading = false, listings = listings, selected = setOf(clean.baseName, flagged.baseName)),
        // Scenario 5: A flagged ride's detail
        SessionsUiState(isLoading = false, listings = listings, detail = flagged),
        // Scenario 6: The privacy notice before sharing everything
        SessionsUiState(
            isLoading = false,
            listings = listings,
            pendingExport = ExportRequest(listOf(clean.baseName, attempt.baseName, flagged.baseName), includeDays = true)
        ),
    )

    private fun listing(baseName: String, summary: SessionSummary) =
        SessionListing(baseName, summary, isOpen = false, sizeBytes = 120_000)
}
