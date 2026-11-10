package com.opensab.domain.audio

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

data class Tone(val hertz: Int, val millis: Int)

/**
 * The shape of a note, and the reason a cue is audible over music without asking for audio focus.
 *
 * A bare square-edged tone clicks at both ends. [attackMillis] is short enough that the onset still
 * reads as percussive — which is what cuts through the music — and long enough not to be a step.
 * [decayMillis] kills the click of the ending.
 */
data class Envelope(val attackMillis: Int, val decayMillis: Int)

/** Pure data: the platform half only pours the samples into a track. */
data class CueRecipe(val tones: List<Tone>, val envelope: Envelope, val gain: Float)

/**
 * The timbre lives here, in pure Kotlin, so the JVM suite can assert that the nine are
 * distinguishable pairwise — the property the whole feature depends on, and one no audio file
 * would let anybody claim.
 *
 * A sine and not a square wave. The intercom mixes to mono and may run a narrowband codec; a square
 * wave's odd harmonics above that band fold back and turn a beep into a rattle inside a helmet.
 * [SAMPLE_RATE_HZ] is 22 050 because the highest tone of the catalogue is 1600 Hz, so doubling it
 * would double the nine buffers without adding a harmonic that exists.
 */
object CueRecipes {

    const val SAMPLE_RATE_HZ = 22_050

    /**
     * A cue that arrives after the movement lies about where the rider is, and the length of the
     * tones is the only half of that delay this side of the intercom can shorten.
     */
    const val MAX_CUE_MILLIS = 200

    /** Shared by the nine, because nothing measured says to tell them apart. */
    val ENVELOPE = Envelope(attackMillis = 3, decayMillis = 10)

    private val TABLE: Map<Cue, CueRecipe> = mapOf(
        // The shortest of the catalogue and the quietest: it sounds once per cursor click, so it is
        // the only one that can wear the rider out.
        Cue.MOVE to CueRecipe(listOf(Tone(1200, 40)), ENVELOPE, gain = 0.35f),
        // Same length as MOVE and far below it in pitch, so the two are told apart without thinking.
        // More gain for the same loudness, because a low tone is heard as quieter.
        Cue.EDGE to CueRecipe(listOf(Tone(400, 40)), ENVELOPE, gain = 0.50f),
        Cue.ENTER to CueRecipe(listOf(Tone(800, 50), Tone(1200, 50)), ENVELOPE, gain = 0.50f),
        Cue.LEAVE to CueRecipe(listOf(Tone(1200, 50), Tone(800, 50)), ENVELOPE, gain = 0.50f),
        // ENTER carried to the end: three rising instead of two.
        Cue.SUCCESS to CueRecipe(
            listOf(Tone(800, 60), Tone(1200, 60), Tone(1600, 60)), ENVELOPE, gain = 0.60f
        ),
        Cue.FAILURE to CueRecipe(listOf(Tone(500, 90), Tone(350, 90)), ENVELOPE, gain = 0.70f),
        // The one tone already verified in the field; keep it untouched.
        Cue.LISTEN to CueRecipe(listOf(Tone(1000, 150)), ENVELOPE, gain = 0.55f),
        // Like ENTER, lower and longer: it is not a page, it is the motorcycle.
        Cue.LINK_UP to CueRecipe(listOf(Tone(600, 80), Tone(900, 80)), ENVELOPE, gain = 0.60f),
        Cue.LINK_DOWN to CueRecipe(listOf(Tone(900, 80), Tone(600, 80)), ENVELOPE, gain = 0.60f)
    )

    fun recipeFor(cue: Cue): CueRecipe = TABLE.getValue(cue)

    fun samplesFor(cue: Cue): ShortArray = samplesOf(recipeFor(cue))

    /**
     * The 16-bit mono PCM of one recipe.
     *
     * The envelope is applied **per tone and not per cue**, so the seam between the 800 and the 1200
     * of [Cue.ENTER] is click-free too and the pair is heard as two notes rather than as a glide.
     */
    fun samplesOf(recipe: CueRecipe): ShortArray {
        // Truncate rather than round: rounding would put the peak one LSB above the recipe's gain
        // for the gains that land on a half-LSB (0.50, 0.55, 0.70), and the peak must never exceed it.
        val peak = (recipe.gain * Short.MAX_VALUE).toInt()
        val out = ShortArray(recipe.tones.sumOf { samplesOfMillis(it.millis) })
        // Each tone's offset comes from the lengths of the previous tones, not from a running var.
        recipe.tones.forEachIndexed { index, tone ->
            val at = recipe.tones.take(index).sumOf { samplesOfMillis(it.millis) }
            val length = samplesOfMillis(tone.millis)
            val attack = samplesOfMillis(recipe.envelope.attackMillis).coerceAtMost(length / 2)
            val decay = samplesOfMillis(recipe.envelope.decayMillis)
                .coerceAtMost(length - attack)
                .coerceAtLeast(1)
            val step = 2.0 * PI * tone.hertz / SAMPLE_RATE_HZ
            for (i in 0 until length) {
                val shape = when {
                    i < attack -> (i + 1).toDouble() / attack
                    i >= length - decay -> (length - i).toDouble() / decay
                    else -> 1.0
                }
                out[at + i] = (sin(step * i) * shape * peak).roundToInt().toShort()
            }
        }
        return out
    }

    private fun samplesOfMillis(millis: Int): Int = SAMPLE_RATE_HZ * millis / 1000
}
