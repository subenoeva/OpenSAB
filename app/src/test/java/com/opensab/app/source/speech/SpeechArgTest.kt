package com.opensab.app.source.speech

import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechQuantity
import com.opensab.domain.speech.SpeechWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The `when` without an `else` in `SpeechPhrases.kt` refuses to compile a quantity or a word nobody
 * translated, so what is left here is the half the compiler cannot see: that every entry points at
 * a resource that exists, and that what the engine put into an argument is still there when the
 * platform half reads it back.
 *
 * Resource ids are read as the integers they are and never resolved.
 */
class SpeechArgTest {

    /**
     * Sweeping the catalogue is what the exhaustive `when` cannot do: it forces a branch per token,
     * but not a resource behind that branch, and a missing resource is a zero id.
     */
    @Test
    fun everyQuantityTokenDeclaresAPlural() {
        for (token in SpeechQuantity.entries) {
            assertNotEquals("SpeechQuantity.$token has no plural resource", 0, token.plural())
        }
    }

    @Test
    fun everyWordTokenDeclaresAString() {
        for (token in SpeechWord.entries) {
            assertNotEquals("SpeechWord.$token has no string resource", 0, token.string())
        }
    }

    /**
     * Two different quantities may not share a plural: they are different nouns, and sharing one
     * would say "21 hours" for a temperature the day somebody copied a line.
     */
    @Test
    fun noTwoQuantitiesShareAPlural() {
        val plurals = SpeechQuantity.entries.map { it.plural() }

        assertEquals("Two quantities share a plural resource", plurals.size, plurals.toSet().size)
    }

    /** Same rule, and the same copied line, for the words. */
    @Test
    fun noTwoWordsShareAString() {
        val strings = SpeechWord.entries.map { it.string() }

        assertEquals("Two words share a string resource", strings.size, strings.toSet().size)
    }

    @Test
    fun aTextArgumentIsTheStringItCarries() {
        assertEquals("Vigo", SpeechArg.Text("Vigo").value)
    }

    @Test
    fun aQuantityKeepsItsCountAndItsToken() {
        val hours = SpeechArg.Quantity(SpeechQuantity.HOURS, 3)

        assertEquals(SpeechQuantity.HOURS, hours.token)
        assertEquals(3, hours.count)
    }

    @Test
    fun aWordKeepsItsToken() {
        val sky = SpeechArg.Word(SpeechWord.WEATHER_SUN_AND_CLOUD)

        assertEquals(SpeechWord.WEATHER_SUN_AND_CLOUD, sky.token)
    }
}
