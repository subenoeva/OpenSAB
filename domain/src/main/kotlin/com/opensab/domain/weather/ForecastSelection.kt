package com.opensab.domain.weather

/**
 * Which of the twenty-four hours the response carries are worth saying out loud.
 *
 * Three slots, three hours apart, and both numbers are decisions rather than accidents: each spoken
 * line lasts about four seconds, and nine hours ahead covers the way back from an afternoon ride. Fewer than [count] is a valid
 * answer and not a failure: at the end of the requested window there is simply nothing further to
 * say.
 *
 * Pure, so that what the rider ends up hearing is decided where a test can reach it and not inside
 * the fetch.
 *
 * @param nowMillis the instant the selection is made from. The first slot is the first one at
 *   least [stepHours] away from it, and every later slot is [stepHours] past the one before — so
 *   the spacing holds whatever the response's own granularity turns out to be.
 */
internal fun selectForecastSegments(
    samples: List<HourlySample>,
    nowMillis: Long,
    stepHours: Int = 3,
    count: Int = 3
): List<WeatherForecastSegment> {
    if (samples.isEmpty() || stepHours <= 0 || count <= 0) return emptyList()

    val step = stepHours * MILLIS_PER_HOUR
    val chosen = mutableListOf<HourlySample>()
    // The response starts at midnight whatever time it is asked, so most of what it carries is
    // already gone: the first slot worth saying is the first one a whole step away from now.
    var nextWanted = nowMillis + step

    for (sample in samples.sortedBy { it.epochSeconds }) {
        if (chosen.size == count) break
        val at = sample.epochSeconds * MILLIS_PER_SECOND
        if (at >= nextWanted) {
            chosen += sample
            nextWanted = at + step
        }
    }

    return chosen.map { sample ->
        WeatherForecastSegment(
            timestamp = sample.epochSeconds * MILLIS_PER_SECOND,
            temperature = sample.temperature,
            caption = sample.condition.caption,
            type = sample.condition.type
        )
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_HOUR = 3_600_000L
