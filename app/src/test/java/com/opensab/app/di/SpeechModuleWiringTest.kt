package com.opensab.app.di

import com.opensab.domain.audio.NoAudioFocus
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallManager
import com.opensab.domain.call.CallStatus
import com.opensab.domain.settings.VoiceVerbosity
import com.opensab.domain.speech.MockSpeechSynthesizer
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechTextResolver
import com.opensab.domain.speech.SpeechToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The module decides what "in a call" means for the voice: an answered call, not a ringing one. */
class SpeechModuleWiringTest {

    private class FakeCallManager(
        override val activeCall: StateFlow<CallInfo?>
    ) : CallManager {
        override fun start(scope: CoroutineScope) = Unit
        override fun stop() = Unit
    }

    private val script = listOf(SpeechRequest(SpeechToken.ROOT_CALL))

    private suspend fun spokenWith(call: CallInfo?, scope: CoroutineScope): MockSpeechSynthesizer {
        val synthesizer = MockSpeechSynthesizer()
        val voice = SpeechModule.providePageVoice(
            resolver = { "Phone" },
            synthesizer = synthesizer,
            audioFocus = NoAudioFocus,
            verbosityFlow = MutableStateFlow(VoiceVerbosity.STANDARD),
            applicationScope = scope,
            callManager = FakeCallManager(MutableStateFlow(call))
        )
        voice.say(script)
        return synthesizer
    }

    @Test
    fun anActiveCallSilencesTheNarration() = runTest {
        val synthesizer = spokenWith(CallInfo("Alice", "123", CallStatus.ACTIVE), backgroundScope)

        assertTrue(synthesizer.utterances.isEmpty())
    }

    @Test
    fun aRingingCallDoesNotSilenceTheNarration() = runTest {
        val synthesizer = spokenWith(CallInfo("Alice", "123", CallStatus.RINGING), backgroundScope)

        assertEquals(listOf("Phone"), synthesizer.utterances.map { it.text })
    }

    @Test
    fun noCallLetsTheNarrationSpeak() = runTest {
        val synthesizer = spokenWith(null, backgroundScope)

        assertEquals(listOf("Phone"), synthesizer.utterances.map { it.text })
    }
}
