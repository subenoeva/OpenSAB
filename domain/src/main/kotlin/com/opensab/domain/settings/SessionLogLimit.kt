package com.opensab.domain.settings

/**
 * The sizes the rider can give the session journal. Stored as megabytes in
 * [AppSettings.sessionLogLimitMb]; any other value, from an older or a tampered document, reads as
 * [DEFAULT_MB] instead of an enum that would have to survive a rename.
 */
object SessionLogLimit {
    val OPTIONS_MB = listOf(25, 50, 100, 200)
    const val DEFAULT_MB = 50

    fun normalize(mb: Int): Int = if (mb in OPTIONS_MB) mb else DEFAULT_MB

    fun bytes(mb: Int): Long = normalize(mb) * 1024L * 1024L
}
