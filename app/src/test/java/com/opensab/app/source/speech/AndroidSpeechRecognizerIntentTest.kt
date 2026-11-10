package com.opensab.app.source.speech

import android.speech.RecognizerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AndroidSpeechRecognizerIntentTest {

    @Test
    fun intentContainsRequiredExtrasAndCallingPackage() {
        val intent = buildSpeechRecognizerIntent(
            callingPackage = "com.opensab",
            locale = Locale.GERMANY
        )

        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)
        )
        assertEquals("com.opensab", intent.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE))
        assertEquals("de-DE", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
    }

    @Test
    fun nullLocaleOmitsLanguageExtra() {
        val intent = buildSpeechRecognizerIntent(
            callingPackage = "com.opensab",
            locale = null
        )

        assertNull(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals("com.opensab", intent.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE))
    }
}
