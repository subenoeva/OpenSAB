package com.opensab.app.source.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceSpeechTextResolverTest {

    @Test
    fun digitsArgumentFormatsSpacedDigitsAndPreservesPlus() {
        val resolved = ResourceSpeechTextResolver.formatDigits("+34612345678")
        assertEquals("+ 3 4 6 1 2 3 4 5 6 7 8", resolved)
    }

    @Test
    fun digitsArgumentWithoutPlusFormatsDigitsOnly() {
        val resolved = ResourceSpeechTextResolver.formatDigits("612345678")
        assertEquals("6 1 2 3 4 5 6 7 8", resolved)
    }

    @Test
    fun digitsArgumentWithFormattingFormatsDigitsOnly() {
        val resolved = ResourceSpeechTextResolver.formatDigits("+34 612-34-56-78")
        assertEquals("+ 3 4 6 1 2 3 4 5 6 7 8", resolved)
    }

    @Test
    fun emptyDigitsReturnsEmptyString() {
        val resolved = ResourceSpeechTextResolver.formatDigits("")
        assertEquals("", resolved)
    }
}
