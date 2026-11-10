package com.opensab.domain.music

import kotlinx.coroutines.flow.StateFlow

/** The media session that currently owns playback on the phone. */
data class MediaSessionSnapshot(
    val packageName: String,
    val metadata: TrackMetadata,
    val status: PlaybackStatus
)

/**
 * Everything above this interface is plain Kotlin and unit-testable on the JVM; the framework
 * implementation, `MediaSessionManagerSource` (`:app`), can only be exercised on a device.
 */
interface MediaSessionSource {

    /** Null while no player holds an active session, or while media access is not granted. */
    val activeSession: StateFlow<MediaSessionSnapshot?>

    /** Subscribes to the system. Callers must pair it with [stop] to release the listeners. */
    fun start()

    fun stop()

    fun play()
    fun pause()
    fun skipToNext()
    fun skipToPrevious()

    /** Wins when several media sessions are active at once. */
    fun setPreferredPackage(packageName: String?) {}
}
