package com.opensab.app.source.speech

import com.opensab.app.R
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The two shapes of the question `MessagePromptPage` asks out loud.
 *
 * There are two tokens and not one because [SpeechRequest] checks arity on construction: a single
 * two-argument token would turn a message from a package with no label into an exception on the
 * bike, and filling the hole with an empty string would say "Message from Carlos on . Shall I read
 * it?". The short variant is a shorter sentence, which is what a missing app name deserves.
 */
class MessagePromptSpeechTest {

    @Test
    fun `should name the sender and the app in the long question`() {
        assertEquals(2, SpeechToken.MESSAGE_PROMPT.arity)

        val request = SpeechRequest(
            SpeechToken.MESSAGE_PROMPT,
            SpeechArg.Text("Carlos"),
            SpeechArg.Text("WhatsApp")
        )

        assertEquals(
            listOf(SpeechArg.Text("Carlos"), SpeechArg.Text("WhatsApp")),
            request.args
        )
    }

    @Test
    fun `should name only the sender in the short question`() {
        assertEquals(1, SpeechToken.MESSAGE_PROMPT_SHORT.arity)

        val request = SpeechRequest(SpeechToken.MESSAGE_PROMPT_SHORT, SpeechArg.Text("Carlos"))

        assertEquals(listOf(SpeechArg.Text("Carlos")), request.args)
    }

    @Test
    fun `should back both questions with real resources`() {
        assertNotEquals(0, SpeechToken.MESSAGE_PROMPT.phrases().tft)
        assertNotEquals(0, SpeechToken.MESSAGE_PROMPT_SHORT.phrases().tft)
    }

    /**
     * The two must not collapse onto the same string: that is exactly the mistake the pair exists
     * to prevent, and the compiler cannot see it.
     */
    @Test
    fun `should not say the same thing twice`() {
        assertNotEquals(
            SpeechToken.MESSAGE_PROMPT.phrases().tft,
            SpeechToken.MESSAGE_PROMPT_SHORT.phrases().tft
        )
        assertEquals(R.string.speech_message_prompt, SpeechToken.MESSAGE_PROMPT.phrases().tft)
        assertEquals(
            R.string.speech_message_prompt_short,
            SpeechToken.MESSAGE_PROMPT_SHORT.phrases().tft
        )
    }
}
