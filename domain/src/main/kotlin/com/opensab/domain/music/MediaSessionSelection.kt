package com.opensab.domain.music

private const val STATE_NONE = 0
private const val STATE_PLAYING = 3

data class SessionCandidate(
    val packageName: String,
    val state: Int
)

/** Priority: playing favorite > any playing session > paused favorite > first allowed session. */
fun selectSessionIndex(
    candidates: List<SessionCandidate>,
    preferredPackage: String?,
    isPackageAllowed: (String) -> Boolean = { MediaPackageFilter.isAllowed(it) }
): Int? {
    if (candidates.isEmpty()) return null

    val preferred = preferredPackage?.takeIf { it.isNotBlank() && isPackageAllowed(it) }
    val preferredIndex = preferred?.let { pkg -> candidates.indexOfFirst { it.packageName == pkg } }
        ?.takeIf { it >= 0 }

    if (preferredIndex != null && candidates[preferredIndex].state == STATE_PLAYING) {
        return preferredIndex
    }

    val playingIndex = candidates.indexOfFirst { it.state == STATE_PLAYING && isPackageAllowed(it.packageName) }
    if (playingIndex >= 0) return playingIndex

    if (preferredIndex != null) return preferredIndex

    val fallbackIndex = candidates.indexOfFirst { isPackageAllowed(it.packageName) }
    if (fallbackIndex >= 0) return fallbackIndex

    return null
}

/**
 * A live media session as choosing one and rebinding to it read it. [state] is the
 * `PlaybackState` constant, `null` when the controller has none yet; [token] is the platform's
 * session token, opaque here: only its equality counts.
 */
data class LiveMediaSession(
    val packageName: String,
    val state: Int?,
    val token: Any
)

/** The index into [sessions] of the one to bind, by [selectSessionIndex]; no state counts as `STATE_NONE`. */
fun selectLiveSession(sessions: List<LiveMediaSession>?, preferredPackage: String?): Int? {
    if (sessions.isNullOrEmpty()) return null
    return selectSessionIndex(
        sessions.map { SessionCandidate(packageName = it.packageName, state = it.state ?: STATE_NONE) },
        preferredPackage
    )
}

/** Same token, same session, whatever its package: binding it again would change nothing. */
fun isSameSession(bound: LiveMediaSession?, next: LiveMediaSession?): Boolean = bound?.token == next?.token
