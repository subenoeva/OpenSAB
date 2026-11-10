package com.opensab.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TunableTest {

    /** The defaults are the constants the code carried before they became settings. */
    @Test
    fun `an untouched store resolves to the values that used to be hard-coded`() {
        val untouched = AppSettings()

        assertEquals(2.seconds, Tunable.REPLY_SENT_LINGER.resolve(untouched))
        assertEquals(15.minutes, Tunable.WEATHER_REFRESH.resolve(untouched))
        assertEquals(10.seconds, Tunable.SAB_MODE_WAIT.resolve(untouched))
    }

    @Test
    fun `every default sits inside its own range and on its own step`() {
        for (tunable in Tunable.entries) {
            assertTrue("$tunable default out of range", tunable.default in tunable.range)
            assertEquals("$tunable default off step", 0, (tunable.default - tunable.range.first) % tunable.step)
            assertEquals("$tunable range off step", 0, (tunable.range.last - tunable.range.first) % tunable.step)
        }
    }

    @Test
    fun `hands over what the rider asked for`() {
        assertEquals(7.seconds, Tunable.REPLY_SENT_LINGER.resolve(AppSettings(replySentLingerSeconds = 7)))
        assertEquals(30.minutes, Tunable.WEATHER_REFRESH.resolve(AppSettings(weatherRefreshMinutes = 30)))
        assertEquals(20.seconds, Tunable.SAB_MODE_WAIT.resolve(AppSettings(sabModeWaitSeconds = 20)))
    }

    /**
     * Clamped on read and never on write: a value from an older version, or from a settings file
     * edited by hand, cannot leave the weather unrefreshed for a day or the wait at zero.
     */
    @Test
    fun `clamps a stored value that is outside the range`() {
        for (tunable in Tunable.entries) {
            assertEquals(tunable.range.first, tunable.valueIn(tunable.write(AppSettings(), -30)))
            assertEquals(tunable.range.first, tunable.valueIn(tunable.write(AppSettings(), 0)))
            assertEquals(tunable.range.last, tunable.valueIn(tunable.write(AppSettings(), 999)))
        }
    }

    @Test
    fun `write stores the value as given and touches no other field`() {
        for (tunable in Tunable.entries) {
            val written = tunable.write(AppSettings(), 999)

            assertEquals(999, tunable.storedIn(written))
            assertEquals(AppSettings(), tunable.write(written, tunable.default))
        }
    }

    @Test
    fun `leaves both ends of the range alone`() {
        for (tunable in Tunable.entries) {
            assertEquals(tunable.range.first, tunable.valueIn(tunable.write(AppSettings(), tunable.range.first)))
            assertEquals(tunable.range.last, tunable.valueIn(tunable.write(AppSettings(), tunable.range.last)))
        }
    }

    /**
     * The second REQUEST_SAB_MODE goes out five seconds after the first. A wait that ends before it
     * would report a cluster that was never asked twice.
     */
    @Test
    fun `the shortest wait for the cluster is longer than the second request`() {
        assertTrue(Tunable.SAB_MODE_WAIT.range.first > 5)
    }

    @Test
    fun `the positions of a slider are the steps between the two ends`() {
        assertEquals(8, Tunable.REPLY_SENT_LINGER.sliderSteps)
        assertEquals(10, Tunable.WEATHER_REFRESH.sliderSteps)
        assertEquals(23, Tunable.SAB_MODE_WAIT.sliderSteps)
    }

    @Test
    fun `a slider position snaps to the nearest step`() {
        assertEquals(15, Tunable.WEATHER_REFRESH.snap(16.9f))
        assertEquals(20, Tunable.WEATHER_REFRESH.snap(17.6f))
        assertEquals(60, Tunable.WEATHER_REFRESH.snap(80f))
        assertEquals(7, Tunable.REPLY_SENT_LINGER.snap(7.4f))
    }
}
