package com.opensab.app.source.speech

import com.opensab.app.R
import com.opensab.domain.speech.SpeechQuantity
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.speech.SpeechWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `when` without an `else` in `SpeechPhrases.kt` already refuses to compile a token nobody
 * gave a phrase to, so what is left here is what the compiler cannot see: that every entry points
 * at a resource that exists, and that the cluster dimension hands over the right one of the two.
 *
 * Resource ids are read as the integers they are and never resolved: the question is which
 * resource the table picks, not what it says.
 */
class SpeechPhrasesTest {

    /**
     * Two real resources rather than two invented integers: what is being asked is which of the
     * two variants comes back, so they only have to differ.
     */
    private val divergingPhrases = SpeechPhrases(
        tft = R.string.speech_weather_forecast_message,
        indicator = R.string.speech_message_read
    )

    /**
     * Sweeping the catalogue is the half the exhaustive `when` cannot cover: it forces a phrase
     * per token, but not a resource behind that phrase, and a missing resource is a zero id.
     */
    @Test
    fun everyTokenDeclaresAPhrase() {
        for (token in SpeechToken.entries) {
            val phrases = token.phrases()

            assertNotEquals("SpeechToken.$token has no TFT resource", 0, phrases.tft)
            assertNotEquals("SpeechToken.$token has no indicator resource", 0, phrases.indicator)
        }
    }

    @Test
    fun aColorClusterTakesTheFullWording() =
        assertEquals(
            R.string.speech_weather_forecast_message,
            divergingPhrases.forCluster(segmentDisplay = false)
        )

    /**
     * The segment cluster is the whole reason the dimension exists: it gets the wording written
     * for it, not the one written for a five-inch screen.
     */
    @Test
    fun aSegmentClusterTakesTheShortWording() =
        assertEquals(
            R.string.speech_message_read,
            divergingPhrases.forCluster(segmentDisplay = true)
        )

    /**
     * The single-resource constructor is the "does not diverge" case, which is every phrase today.
     * It has to fill both variants and not leave the indicator one empty, because the fallback
     * that would hide such a hole only covers an unknown cluster, not an identified one.
     */
    @Test
    fun theSingleResourceConstructorFillsBothVariants() {
        val single = SpeechPhrases(R.string.speech_message_read)

        assertEquals(
            SpeechPhrases(R.string.speech_message_read, R.string.speech_message_read),
            single
        )
    }

    @Test
    fun callConfirmPointsAtItsRealStringResource() {
        val phrases = SpeechToken.CALL_CONFIRM.phrases()
        assertEquals(R.string.speech_call_confirm, phrases.tft)
        assertEquals(R.string.speech_call_confirm, phrases.indicator)
    }

    @Test
    fun callRingingTokensHaveCorrectArity() {
        assertEquals(1, SpeechToken.CALL_RINGING.arity)
        assertEquals(0, SpeechToken.CALL_RINGING_UNKNOWN.arity)
    }

    @Test
    fun tokensWithHelpDeclareValidHelpResource() {
        val expectedWithHelp = setOf(
            SpeechToken.WEATHER_FORECAST_MESSAGE,
            SpeechToken.MESSAGE_PROMPT,
            SpeechToken.MESSAGE_PROMPT_SHORT,
            SpeechToken.MESSAGE_READ,
            SpeechToken.MESSAGE_CONFIRM,
            SpeechToken.CALL_CONFIRM,
            SpeechToken.CALL_RINGING,
            SpeechToken.CALL_RINGING_UNKNOWN,
            SpeechToken.NAV_CONFIRM
        )

        for (token in expectedWithHelp) {
            val phrases = token.phrases()
            val helpRes = phrases.help
            assertNotNull("SpeechToken.$token must declare help resource", helpRes)
            assertNotEquals("SpeechToken.$token help resource must not be 0", 0, helpRes)
        }
    }

    @Test
    fun navConfirmHasCorrectArityAndResources() {
        assertEquals(1, SpeechToken.NAV_CONFIRM.arity)
        val phrases = SpeechToken.NAV_CONFIRM.phrases()
        assertEquals(R.string.speech_nav_confirm, phrases.tft)
        assertEquals(R.string.speech_nav_confirm, phrases.indicator)
        assertEquals(R.string.speech_nav_confirm_help, phrases.help)
    }

    @Test
    fun tokensWithoutHelpDeclareNullHelp() {
        val expectedWithoutHelp = setOf(
            SpeechToken.WEATHER_FORECAST_LINE,
            SpeechToken.WEATHER_CURRENT_LINE,
            SpeechToken.NAV_MANEUVER
        )

        for (token in expectedWithoutHelp) {
            val phrases = token.phrases()
            assertNull("SpeechToken.$token must not declare help resource", phrases.help)
        }
    }

    @Test
    fun newTokensDeclarePhrasesAndExpectedHelpPresence() {
        assertEquals(R.plurals.speech_quantity_percent, SpeechQuantity.PERCENT.plural())
        assertEquals(R.string.speech_word_menu_phone, SpeechWord.MENU_PHONE.string())
        assertEquals(R.string.speech_word_menu_messages, SpeechWord.MENU_MESSAGES.string())
        assertEquals(R.string.speech_word_menu_navigation, SpeechWord.MENU_NAVIGATION.string())

        // Verified help presence
        assertNotNull(SpeechToken.ROOT_WEATHER.phrases().help)
        assertNull(SpeechToken.CALL_MENU_VOICE_DISABLED.phrases().help)
        assertNotNull(SpeechToken.VOICE_NOTHING_UNDERSTOOD.phrases().help)
        assertNotNull(SpeechToken.NAV_NOTHING_UNDERSTOOD.phrases().help)
        assertNotNull(SpeechToken.VOICE_NO_MATCH.phrases().help)
        assertNotNull(SpeechToken.CALL_NOT_PLACED.phrases().help)
        assertNotNull(SpeechToken.MESSAGE_NOT_SENT.phrases().help)
        assertNotNull(SpeechToken.REPLY_NOT_SENT.phrases().help)
        assertNotNull(SpeechToken.NAV_FAILED.phrases().help)
    }

    /** The same failure is retried with different keys on different pages, so its help cannot be shared. */
    @Test
    fun failuresRetriedWithDifferentKeysDeclareDifferentHelp() {
        assertNotEquals(SpeechToken.VOICE_FAILED.phrases().help, SpeechToken.NAV_FAILED.phrases().help)
        assertNotEquals(SpeechToken.MESSAGE_NOT_SENT.phrases().help, SpeechToken.REPLY_NOT_SENT.phrases().help)
    }
}
