package com.opensab.domain.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueRecipeTest {

    private fun peakOf(cue: Cue): Int =
        CueRecipes.samplesFor(cue).maxOf { abs(it.toInt()) }

    private fun millisOf(cue: Cue): Int =
        CueRecipes.recipeFor(cue).tones.sumOf { it.millis }

    @Test
    fun `should have a recipe for every cue`() {
        Cue.entries.forEach { cue ->
            assertTrue("$cue has no tones", CueRecipes.recipeFor(cue).tones.isNotEmpty())
        }
    }

    @Test
    fun `should give no two cues the same tones`() {
        // The whole reason the tones are synthesised instead of shipped as files: with an .ogg per
        // cue the only provable thing is that they point at different resources.
        val signatures = Cue.entries.map { CueRecipes.recipeFor(it).tones }

        assertEquals(Cue.entries.size, signatures.toSet().size)
    }

    @Test
    fun `should synthesise as many samples as the recipe lasts`() {
        assertEquals(CueRecipes.SAMPLE_RATE_HZ * 40 / 1000, CueRecipes.samplesFor(Cue.MOVE).size)
        assertEquals(CueRecipes.SAMPLE_RATE_HZ * 180 / 1000, CueRecipes.samplesFor(Cue.SUCCESS).size)
    }

    @Test
    fun `should start and end at silence`() {
        Cue.entries.forEach { cue ->
            val samples = CueRecipes.samplesFor(cue)
            val peak = peakOf(cue)
            assertEquals("$cue clicks on the way in", 0, samples.first().toInt())
            assertTrue("$cue clicks on the way out", abs(samples.last().toInt()) < peak / 10)
        }
    }

    @Test
    fun `should reach the gain of its recipe and no more`() {
        Cue.entries.forEach { cue ->
            val wanted = (CueRecipes.recipeFor(cue).gain * Short.MAX_VALUE).toInt()
            val peak = peakOf(cue)
            assertTrue("$cue peaks at $peak, wanted $wanted", peak in (wanted * 95 / 100)..wanted)
        }
    }

    @Test
    fun `should shape every tone of a cue and not only the first`() {
        // The envelope is per tone, so the seam between the 800 and the 1200 of
        // ENTER comes back to near silence and the two are heard as two notes, not as a glide.
        val recipe = CueRecipes.recipeFor(Cue.ENTER)
        val seam = CueRecipes.SAMPLE_RATE_HZ * recipe.tones.first().millis / 1000
        val samples = CueRecipes.samplesFor(Cue.ENTER)
        val peak = peakOf(Cue.ENTER)

        assertTrue("the first tone does not fade out", abs(samples[seam - 1].toInt()) < peak / 8)
        assertTrue("the second tone does not fade in", abs(samples[seam].toInt()) < peak / 8)

        // The attack ramp itself, not just the seam between tones: `sin(step * 0) == 0.0`
        // regardless of the envelope, so the very first sample proves nothing about it. The first
        // sine peak of MOVE's 1200 Hz tone falls near sample SAMPLE_RATE_HZ / (4 * hertz); with
        // the ramp in place it still sits well under a quarter of the peak, where an
        // `attackMillis = 0` tone would already be near its own ceiling.
        val moveTone = CueRecipes.recipeFor(Cue.MOVE).tones.single()
        val movePeakIndex = CueRecipes.SAMPLE_RATE_HZ / (4 * moveTone.hertz)
        val moveSamples = CueRecipes.samplesFor(Cue.MOVE)
        val movePeak = peakOf(Cue.MOVE)
        assertTrue(
            "MOVE's onset is not ramped",
            abs(moveSamples[movePeakIndex].toInt()) < movePeak / 4
        )
    }

    @Test
    fun `should mirror the two pairs of the vocabulary`() {
        // "Up is entering and coming out well, down is leaving and failing".
        // It is recitable only if the pairs really are each other's reflection.
        assertEquals(
            CueRecipes.recipeFor(Cue.ENTER).tones.reversed(),
            CueRecipes.recipeFor(Cue.LEAVE).tones
        )
        assertEquals(
            CueRecipes.recipeFor(Cue.LINK_UP).tones.reversed(),
            CueRecipes.recipeFor(Cue.LINK_DOWN).tones
        )
    }

    @Test
    fun `should carry more gain on the low cues than on the high ones`() {
        // Equal loudness: at the same amplitude a low tone is heard as quieter, and both are heard
        // through a helmet over an engine.
        assertTrue(CueRecipes.recipeFor(Cue.EDGE).gain > CueRecipes.recipeFor(Cue.MOVE).gain)
        assertTrue(CueRecipes.recipeFor(Cue.FAILURE).gain > CueRecipes.recipeFor(Cue.SUCCESS).gain)
    }

    @Test
    fun `should keep the two cursor cues the shortest of the catalogue`() {
        assertEquals(40, millisOf(Cue.MOVE))
        assertEquals(40, millisOf(Cue.EDGE))
        val others = Cue.entries.filter { it != Cue.MOVE && it != Cue.EDGE }
        assertTrue(others.all { millisOf(it) > 40 })
    }

    @Test
    fun `should keep every cue inside the latency budget`() {
        // A cue that arrives late lies about where you are, and the tones are the only half of that
        // this side of the intercom can shorten.
        Cue.entries.forEach { cue ->
            assertTrue("$cue lasts ${millisOf(cue)} ms", millisOf(cue) <= CueRecipes.MAX_CUE_MILLIS)
        }
    }

    @Test
    fun `should never clip`() {
        // Both checks are needed: the peak check catches a synthesiser that overshoots its recipe,
        // the gain check catches a recipe that asks for more than the format holds. The second is
        // needed because Int.toShort() wraps instead of clamping, so a gain past 1.0 folds into
        // an arbitrary 16-bit value instead of exceeding Short.MAX_VALUE.
        Cue.entries.forEach { cue ->
            val recipe = CueRecipes.recipeFor(cue)
            assertTrue("$cue peaks outside the range", peakOf(cue) <= Short.MAX_VALUE.toInt())
            assertTrue("$cue asks for more gain than the format holds", recipe.gain <= 1.0f)
        }
    }

    @Test
    fun `should synthesise the same samples every time`() {
        Cue.entries.forEach { cue ->
            assertTrue(
                "$cue is not deterministic",
                CueRecipes.samplesFor(cue).contentEquals(CueRecipes.samplesFor(cue))
            )
        }
    }
}
