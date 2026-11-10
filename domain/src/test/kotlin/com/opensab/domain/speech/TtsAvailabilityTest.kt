package com.opensab.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a phone with no text-to-speech engine, or with the language still undownloaded, does to a
 * page that wanted to talk. Nothing — `speak()` returns and does not throw, and the weather page
 * carries on painting.
 *
 * The decision lives in a pure function precisely so it can be pinned here instead of on the bike,
 * where reproducing "no engine installed" means uninstalling the engine. Half of these cases are
 * the trap of the shared `-1`: the framework spends the same number on "the engine failed" and on
 * "the language has no data", so the mapper reads two integers and the tests read them in pairs.
 */
class TtsAvailabilityTest {

    @Test
    fun aFailedInitIsNoEngineWhateverTheLanguageSays() {
        val everyLanguageStatus = listOf(
            TtsStatusCodes.LANG_MISSING_DATA,
            TtsStatusCodes.LANG_NOT_SUPPORTED,
            TtsStatusCodes.LANG_AVAILABLE,
            TtsStatusCodes.LANG_COUNTRY_AVAILABLE,
            TtsStatusCodes.LANG_COUNTRY_VAR_AVAILABLE
        )
        for (languageStatus in everyLanguageStatus) {
            assertEquals(
                "an engine that failed to init has no language to report, so language status " +
                    "$languageStatus says nothing about whether there is a voice",
                TtsAvailability.NO_ENGINE,
                ttsAvailabilityOf(TtsStatusCodes.ERROR, languageStatus)
            )
        }
    }

    @Test
    fun anAvailableLanguageIsReady() {
        assertEquals(
            TtsAvailability.READY,
            ttsAvailabilityOf(TtsStatusCodes.SUCCESS, TtsStatusCodes.LANG_AVAILABLE)
        )
    }

    @Test
    fun aCountryVariantIsAlsoReady() {
        val betterThanPlainLanguage = listOf(
            TtsStatusCodes.LANG_COUNTRY_AVAILABLE,
            TtsStatusCodes.LANG_COUNTRY_VAR_AVAILABLE
        )
        for (languageStatus in betterThanPlainLanguage) {
            assertEquals(
                "language status $languageStatus is a closer match than LANG_AVAILABLE, not a " +
                    "failure to match",
                TtsAvailability.READY,
                ttsAvailabilityOf(TtsStatusCodes.SUCCESS, languageStatus)
            )
        }
    }

    @Test
    fun missingLanguageDataIsNotReady() {
        assertEquals(
            "this -1 came from setLanguage, not from onInit: the engine is there and the language " +
                "is not downloaded",
            TtsAvailability.MISSING_DATA,
            ttsAvailabilityOf(TtsStatusCodes.SUCCESS, TtsStatusCodes.LANG_MISSING_DATA)
        )
    }

    @Test
    fun anUnsupportedLanguageIsNotReady() {
        assertEquals(
            TtsAvailability.NOT_SUPPORTED,
            ttsAvailabilityOf(TtsStatusCodes.SUCCESS, TtsStatusCodes.LANG_NOT_SUPPORTED)
        )
    }

    @Test
    fun onlyReadyCanSpeak() {
        for (availability in TtsAvailability.entries) {
            if (availability == TtsAvailability.READY) {
                assertTrue("READY is the one state with a voice", availability.canSpeak)
            } else {
                assertFalse("$availability must stay silent, never speak", availability.canSpeak)
            }
        }
    }
}
