package com.opensab.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine names a phrase instead of writing one, because it cannot see `R`, and the only thing
 * it can get wrong on its own is how many arguments it hands over. A phrase with three
 * placeholders spoken with two arguments is a defect that shows up on the bike and nowhere else,
 * so a mismatched request refuses to exist rather than reach the resolver.
 *
 * The arguments are typed too, for the same reason the phrase is: a plural is something only the
 * platform half can say, so the engine names the quantity and hands over the number.
 */
class SpeechRequestTest {

    private val threeHours = SpeechArg.Quantity(SpeechQuantity.HOURS, 3)
    private val partlyCloudy = SpeechArg.Word(SpeechWord.WEATHER_SUN_AND_CLOUD)
    private val twentyOneDegrees = SpeechArg.Quantity(SpeechQuantity.DEGREES, 21)

    @Test
    fun buildsARequestWithTheExactNumberOfArguments() {
        val request = SpeechRequest(
            SpeechToken.WEATHER_FORECAST_LINE,
            listOf(threeHours, partlyCloudy, twentyOneDegrees)
        )

        assertEquals(listOf(threeHours, partlyCloudy, twentyOneDegrees), request.args)
    }

    @Test
    fun rejectsARequestWithTooFewArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechRequest(SpeechToken.WEATHER_FORECAST_LINE, listOf(threeHours, partlyCloudy))
        }
    }

    @Test
    fun rejectsARequestWithTooManyArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechRequest(
                SpeechToken.MESSAGE_READ,
                listOf(SpeechArg.Text("the body"), SpeechArg.Text("and a leftover"))
            )
        }
    }

    /**
     * No token declares an arity of zero today, so the rule is fixed by sweeping the catalogue
     * rather than by inventing a token nothing asked for: every token refuses every count that is
     * not its own, and the zero-arity phrase added tomorrow is already covered on the day it lands.
     */
    @Test
    fun rejectsArgumentsOnATokenThatTakesNone() {
        for (token in SpeechToken.entries) {
            for (size in 0..token.arity + 1) {
                if (size == token.arity) continue
                assertThrows(
                    "SpeechToken.$token takes ${token.arity} arguments and accepted $size",
                    IllegalArgumentException::class.java
                ) {
                    SpeechRequest(token, List(size) { SpeechArg.Text("arg$it") })
                }
            }
        }
    }

    /**
     * The vararg constructor is sugar for the call sites in the engine, not a second contract: it
     * delegates to the list one and builds the same request, so there is one arity check and not
     * two.
     */
    @Test
    fun theVarargConstructorTakesTypedArgumentsAndMatchesTheListOne() {
        val fromVarargs = SpeechRequest(
            SpeechToken.WEATHER_FORECAST_LINE,
            threeHours,
            partlyCloudy,
            twentyOneDegrees
        )
        val fromList = SpeechRequest(
            SpeechToken.WEATHER_FORECAST_LINE,
            listOf(threeHours, partlyCloudy, twentyOneDegrees)
        )

        assertEquals(fromList, fromVarargs)
    }

    /**
     * Sweeping [SpeechToken.entries] is what makes this a catalogue test and not three examples: a
     * negative arity would be a phrase no request could ever satisfy.
     */
    @Test
    fun everyTokenDeclaresANonNegativeArity() {
        for (token in SpeechToken.entries) {
            assertTrue("SpeechToken.$token declares arity ${token.arity}", token.arity >= 0)
        }
    }

    /**
     * The current reading is said with a line of its own and not with [SpeechToken.WEATHER_FORECAST_LINE],
     * whose first argument is a plural of hours: saying it that way would mean "in 0 hours".
     */
    @Test
    fun theCurrentLineTakesTheConditionAndTheTemperatureAndNoHour() {
        assertEquals(2, SpeechToken.WEATHER_CURRENT_LINE.arity)

        val request = SpeechRequest(
            SpeechToken.WEATHER_CURRENT_LINE,
            partlyCloudy,
            twentyOneDegrees
        )

        assertEquals(listOf(partlyCloudy, twentyOneDegrees), request.args)
    }

    @Test
    fun digitsArgumentHoldsPhoneDigits() {
        val arg = SpeechArg.Digits("+34612345678")
        assertEquals("+34612345678", arg.value)
    }

    @Test
    fun newSpeechTokensHaveCorrectArity() {
        assertEquals(2, SpeechToken.ROOT_WEATHER.arity)
        assertEquals(0, SpeechToken.ROOT_WEATHER_EMPTY.arity)
        assertEquals(0, SpeechToken.ROOT_NAVIGATION.arity)
        assertEquals(0, SpeechToken.ROOT_NAVIGATION_GUIDING.arity)
        assertEquals(0, SpeechToken.ROOT_CALL.arity)
        assertEquals(1, SpeechToken.ROOT_MESSAGE_UNREAD.arity)
        assertEquals(0, SpeechToken.ROOT_MESSAGE.arity)
        assertEquals(1, SpeechToken.ROOT_MUSIC_PLAYING.arity)
        assertEquals(0, SpeechToken.ROOT_MUSIC.arity)
        assertEquals(0, SpeechToken.ROOT_VOLUME.arity)
        assertEquals(1, SpeechToken.MENU_TITLE.arity)
        assertEquals(0, SpeechToken.CALL_MENU_VOICE.arity)
        assertEquals(0, SpeechToken.CALL_MENU_VOICE_DISABLED.arity)
        assertEquals(0, SpeechToken.CALL_MENU_FAVORITES.arity)
        assertEquals(0, SpeechToken.CALL_MENU_FAVORITES_EMPTY.arity)
        assertEquals(0, SpeechToken.MESSAGE_MENU_VOICE.arity)
        assertEquals(0, SpeechToken.MESSAGE_MENU_VOICE_DISABLED.arity)
        assertEquals(0, SpeechToken.MESSAGE_MENU_FAVORITES.arity)
        assertEquals(0, SpeechToken.MESSAGE_MENU_FAVORITES_EMPTY.arity)
        assertEquals(0, SpeechToken.NAV_MENU_VOICE.arity)
        assertEquals(0, SpeechToken.MUSIC_LIVE.arity)
        assertEquals(0, SpeechToken.MUSIC_LOADING.arity)
        assertEquals(0, SpeechToken.MUSIC_ERROR.arity)
        assertEquals(1, SpeechToken.VOLUME_LEVEL.arity)
        assertEquals(0, SpeechToken.VOLUME_MUTED.arity)
        assertEquals(0, SpeechToken.VOICE_NOTHING_UNDERSTOOD.arity)
        assertEquals(0, SpeechToken.NAV_NOTHING_UNDERSTOOD.arity)
        assertEquals(0, SpeechToken.VOICE_FAILED.arity)
        assertEquals(1, SpeechToken.VOICE_NO_MATCH.arity)
        assertEquals(1, SpeechToken.CALL_NOT_PLACED.arity)
        assertEquals(1, SpeechToken.MESSAGE_CHOOSING_REPLY.arity)
        assertEquals(0, SpeechToken.MESSAGE_SENT.arity)
        assertEquals(0, SpeechToken.MESSAGE_NOT_SENT.arity)
        assertEquals(0, SpeechToken.REPLY_NOT_SENT.arity)
        assertEquals(0, SpeechToken.NAV_FAILED.arity)
        assertEquals(0, SpeechToken.NAV_NO_APP_AVAILABLE.arity)
        assertEquals(0, SpeechToken.NAV_AMBIGUOUS_QUERY.arity)
        assertEquals(0, SpeechToken.NAV_UNLOCK_PHONE.arity)
    }
}
