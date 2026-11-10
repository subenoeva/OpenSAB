package com.opensab.domain.settings

import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * The numbers of taste: waits and intervals the rider may move, each with the range it is allowed
 * and the value it has when nobody touched it.
 *
 * **Clamped on read and never on write**, on purpose. A value from an older version of the app, or
 * from a hand-edited settings file, still comes out inside the range; clamping at write time would
 * leave such a value in the store, where the next reader would trust it.
 *
 * Measured timeouts are deliberately absent: the GATT, bond and Receipt windows, the 5 s keepalive
 * and the second REQUEST_SAB_MODE are facts about the BTU, and a field trace taken with a moved one
 * cannot be compared with any other.
 */
enum class Tunable(
    val range: IntRange,
    val default: Int,
    val step: Int,
    private val unit: DurationUnit
) {
    /**
     * How long "Sent" holds the cluster before giving the screen back. A second is the shortest
     * that reads as an answer rather than a flicker; ten is the longest that is not the rider
     * waiting for his own dashboard.
     */
    REPLY_SENT_LINGER(range = 1..10, default = 2, step = 1, unit = DurationUnit.SECONDS),

    /** How often a settled weather reading is fetched again during a session. */
    WEATHER_REFRESH(range = 5..60, default = 15, step = 5, unit = DurationUnit.MINUTES),

    /**
     * How long the cluster is given to hand its screen over before the app says it did not. The
     * floor stays above the 5 s at which the session asks a second time.
     */
    SAB_MODE_WAIT(range = 6..30, default = 10, step = 1, unit = DurationUnit.SECONDS);

    /** What the store holds, unclamped. */
    fun storedIn(settings: AppSettings): Int = when (this) {
        REPLY_SENT_LINGER -> settings.replySentLingerSeconds
        WEATHER_REFRESH -> settings.weatherRefreshMinutes
        SAB_MODE_WAIT -> settings.sabModeWaitSeconds
    }

    /** Stores [value] as given: the range belongs to the readers. */
    fun write(settings: AppSettings, value: Int): AppSettings = when (this) {
        REPLY_SENT_LINGER -> settings.copy(replySentLingerSeconds = value)
        WEATHER_REFRESH -> settings.copy(weatherRefreshMinutes = value)
        SAB_MODE_WAIT -> settings.copy(sabModeWaitSeconds = value)
    }

    /** The value in effect, in this tunable's own unit. */
    fun valueIn(settings: AppSettings): Int = storedIn(settings).coerceIn(range)

    fun resolve(settings: AppSettings): Duration = valueIn(settings).toDuration(unit)

    /** Intermediate positions of a slider running from one end of [range] to the other. */
    val sliderSteps: Int get() = (range.last - range.first) / step - 1

    fun snap(position: Float): Int {
        val stepsFromStart = ((position - range.first) / step).roundToInt()
        return (range.first + stepsFromStart * step).coerceIn(range)
    }
}
