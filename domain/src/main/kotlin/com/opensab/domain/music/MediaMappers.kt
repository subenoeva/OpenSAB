package com.opensab.domain.music

import kotlin.math.roundToInt

/**
 * The `PlaybackState` codes are redeclared here instead of referenced from
 * `android.media.session.PlaybackState` on purpose: these mappers are covered by JVM unit tests,
 * which run against the stubbed `android.jar` where framework symbols are not usable.
 */
private const val STATE_NONE = 0
private const val STATE_STOPPED = 1
private const val STATE_PAUSED = 2
private const val STATE_PLAYING = 3
private const val STATE_BUFFERING = 6

/**
 * Buffering counts as playing: the rider pressed play and the right icon must already show the
 * pause glyph, otherwise the dash flickers between icons on every stream hiccup. Any code the
 * framework may add in the future degrades to [PlaybackStatus.NONE].
 */
fun playbackStatusOf(frameworkState: Int): PlaybackStatus = when (frameworkState) {
    STATE_PLAYING, STATE_BUFFERING -> PlaybackStatus.PLAYING
    STATE_PAUSED -> PlaybackStatus.PAUSED
    STATE_STOPPED -> PlaybackStatus.STOPPED
    STATE_NONE -> PlaybackStatus.NONE
    else -> PlaybackStatus.NONE
}

/** A zero [maxIndex] means the stream reports no steps at all: silent rather than divide by zero. */
fun volumePercentOf(index: Int, maxIndex: Int): Int {
    if (maxIndex <= 0) return 0
    val percent = (index * 100f / maxIndex).roundToInt()
    return percent.coerceIn(0, 100)
}
