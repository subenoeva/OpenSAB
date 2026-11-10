package com.opensab.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechAssemblerTest {

    @Test
    fun `should return essential unchanged when help is null or empty`() {
        assertEquals("The weather in Vigo", assembleSpeechText("The weather in Vigo", ".", null))
        assertEquals("The weather in Vigo", assembleSpeechText("The weather in Vigo", ".", ""))
        assertEquals("The weather in Vigo", assembleSpeechText("  The weather in Vigo  ", ".", "   "))
    }

    @Test
    fun `should return help when essential is blank`() {
        assertEquals("Press Select to answer.", assembleSpeechText("", ".", "Press Select to answer."))
        assertEquals("Press Select to answer.", assembleSpeechText("   ", ".", "  Press Select to answer.  "))
    }

    @Test
    fun `should append pause and help when essential has no terminal punctuation`() {
        val result = assembleSpeechText(
            essential = "Call from Carlos",
            pause = ".",
            help = "Press Select to answer."
        )
        assertEquals("Call from Carlos. Press Select to answer.", result)
    }

    @Test
    fun `should not duplicate pause when essential already ends with period`() {
        val result = assembleSpeechText(
            essential = "Call from Carlos.",
            pause = ".",
            help = "Press Select to answer."
        )
        assertEquals("Call from Carlos. Press Select to answer.", result)
    }

    @Test
    fun `should not duplicate pause when essential ends with question mark`() {
        val result = assembleSpeechText(
            essential = "Message from Carlos. Shall I read it?",
            pause = ".",
            help = "Press Select to listen. Press Back to dismiss."
        )
        assertEquals(
            "Message from Carlos. Shall I read it? Press Select to listen. Press Back to dismiss.",
            result
        )
    }

    @Test
    fun `should not duplicate pause when essential ends with exclamation mark`() {
        val result = assembleSpeechText(
            essential = "Important alert!",
            pause = ".",
            help = "Press Select to dismiss."
        )
        assertEquals("Important alert! Press Select to dismiss.", result)
    }

    @Test
    fun `should fallback to period if pause is empty and essential lacks punctuation`() {
        val result = assembleSpeechText(
            essential = "Call from Carlos",
            pause = "",
            help = "Press Select to answer."
        )
        assertEquals("Call from Carlos. Press Select to answer.", result)
    }
}
