package com.opensab.logging.journal

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Rejection(val requestId: Int, val status: String, val errorPosition: String)

@Serializable
data class LinkCue(
    val cue: String,
    val route: String,
    val channel: String? = null,
    val volume: Int? = null,
    val maxVolume: Int? = null,
    val muted: Boolean? = null,
    val interruptions: String? = null,
)

@Serializable
enum class Attention { NO_SAB_MODE, REJECTED_FRAME, UNKNOWN_EXCEPTION, TRUNCATED, MISSING_CLOSED, DROPPED_ENTRIES, SUMMARY_FAILED }

@Serializable
data class SummaryFailure(val entryIndex: Int, val firstLine: String, val exception: String)

/** A session's whole summary, written next to its `.log` file. Produced by [SessionSummarizer]. */
@Serializable
data class SessionSummary(
    val summarizerVersion: Int,
    val baseName: String,
    val sessionNumber: Int? = null,
    val openedAt: String? = null,
    val zone: String? = null,
    val closedAt: String? = null,
    val durationMs: Long? = null,
    val origin: String? = null,
    val closeKind: CloseKind = CloseKind.TRUNCATED,
    val closeReason: String? = null,
    val closeActions: Int? = null,
    val closedInMs: Long? = null,
    val connected: Boolean = false,
    val sabModeGranted: Boolean = false,
    val sabModeLatencyMs: Long? = null,
    val sabModeNotGrantedWarnings: Int = 0,
    val projectionFrames: Int = 0,
    val rejections: List<Rejection> = emptyList(),
    val warnings: Int = 0,
    val errors: Int = 0,
    val exceptionTypes: List<String> = emptyList(),
    val calls: Int = 0,
    val callsGone: Int = 0,
    val messages: Int = 0,
    val navigationManeuvers: Int = 0,
    val weatherUpdates: Int = 0,
    val weatherRestored: Int = 0,
    val indicatorChanges: Int = 0,
    val handlebarPresses: Int = 0,
    val mirrorPresses: Int = 0,
    val linkCues: List<LinkCue> = emptyList(),
    val linkCuesSkipped: Int = 0,
    val droppedEntries: Int = 0,
    val attention: List<Attention> = emptyList(),
    val notes: List<String> = emptyList(),
    val failure: SummaryFailure? = null,
)

const val SUMMARIZER_VERSION = 2

/** Reads and writes [SessionSummary] as the `.json` next to a session's `.log` file. */
object SessionSummaryCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(summary: SessionSummary): String = json.encodeToString(summary)

    /** `null` if [text] is not a readable [SessionSummary]. */
    fun decode(text: String): SessionSummary? = try {
        json.decodeFromString<SessionSummary>(text)
    } catch (e: SerializationException) {
        null
    }
}
