package com.opensab.domain.weather

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the rider hears is decided here, so it is decided where a test can reach it: three slots,
 * three hours apart, counted from the moment the reading was taken and not from the top of the
 * hour.
 */
class ForecastSelectionTest {

    private val hour = 3_600L

    /** A full day of hourly slots, the way `forecast_days=2` answers it, starting at midnight. */
    private fun dayOfSamples(hours: Int = 24, from: Long = 0L): List<HourlySample> =
        (0 until hours).map { index ->
            HourlySample(
                epochSeconds = from + index * hour,
                temperature = 10 + index,
                condition = WmoConditionMapper.map(0)
            )
        }

    /** 10:30, so no slot lands exactly on `now + 3h` and the rounding rule has to show itself. */
    private val halfPastTen = (10 * hour + 1800) * 1000

    @Test
    fun picksThreeSlotsThreeHoursApart() {
        val segments = selectForecastSegments(dayOfSamples(), nowMillis = halfPastTen)

        assertEquals(3, segments.size)
        assertEquals(listOf(14 * hour * 1000, 17 * hour * 1000, 20 * hour * 1000), segments.map { it.timestamp })
    }

    /**
     * The response starts at midnight whatever time it is asked, so most of what it carries is
     * already gone. The first slot said out loud is the first one at least three hours away, which
     * at 10:30 is 14:00 and not 13:00.
     */
    @Test
    fun skipsEverySlotAlreadyGoneAndEverySlotTooCloseToNow() {
        val segments = selectForecastSegments(dayOfSamples(), nowMillis = halfPastTen)

        assertEquals(14 * hour * 1000, segments.first().timestamp)
    }

    /** Nothing further to say is a valid answer: the window ran out, the reading did not. */
    @Test
    fun fewerSlotsThanAskedForIsAValidSelection() {
        val segments = selectForecastSegments(dayOfSamples(hours = 18), nowMillis = halfPastTen)

        assertEquals(2, segments.size)
        assertEquals(listOf(14 * hour * 1000, 17 * hour * 1000), segments.map { it.timestamp })
    }

    @Test
    fun noSamplesIsNoSegments() {
        assertEquals(emptyList<WeatherForecastSegment>(), selectForecastSegments(emptyList(), 0L))
    }

    @Test
    fun aWindowThatIsAllPastIsNoSegments() {
        val segments = selectForecastSegments(dayOfSamples(), nowMillis = 23 * hour * 1000)

        assertEquals(emptyList<WeatherForecastSegment>(), segments)
    }

    /** The segment says what its slot said: same caption, same icon family, same table. */
    @Test
    fun aSegmentCarriesTheConditionAndTheTypeOfItsSample() {
        val samples = listOf(
            HourlySample(
                epochSeconds = 20 * hour,
                temperature = 13,
                condition = WmoConditionMapper.map(61)
            )
        )

        val segment = selectForecastSegments(samples, nowMillis = halfPastTen).single()

        assertEquals(
            WeatherForecastSegment(
                timestamp = 20 * hour * 1000,
                temperature = 13,
                caption = WmoCaption.RAIN,
                type = WeatherConditionType.RAIN
            ),
            segment
        )
    }

    /**
     * The spacing is measured from the slot that was picked and not from a fixed grid, so it holds
     * even if the response ever answers something other than one slot per hour.
     */
    @Test
    fun theSpacingIsMeasuredFromTheSlotBeforeAndNotFromAFixedGrid() {
        val sparse = listOf(
            HourlySample(14 * hour, 20, WmoConditionMapper.map(0)),
            HourlySample(16 * hour, 19, WmoConditionMapper.map(0)),
            HourlySample(18 * hour, 18, WmoConditionMapper.map(0)),
            HourlySample(21 * hour, 15, WmoConditionMapper.map(0))
        )

        val segments = selectForecastSegments(sparse, nowMillis = halfPastTen)

        assertEquals(listOf(14 * hour * 1000, 18 * hour * 1000, 21 * hour * 1000), segments.map { it.timestamp })
    }

    @Test
    fun segmentsComeBackInChronologicalOrderWhateverOrderTheyArrivedIn() {
        val segments = selectForecastSegments(dayOfSamples().reversed(), nowMillis = halfPastTen)

        assertEquals(listOf(14 * hour * 1000, 17 * hour * 1000, 20 * hour * 1000), segments.map { it.timestamp })
    }
}
