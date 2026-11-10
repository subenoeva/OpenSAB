package com.opensab.cluster.nav

import com.opensab.domain.speech.RecognitionFailure

internal sealed interface NavVoiceState {
    data object Listening : NavVoiceState
    data class Confirming(val query: String) : NavVoiceState
    data class Launching(val query: String) : NavVoiceState
    data object NothingUnderstood : NavVoiceState
    data class Failed(val reason: RecognitionFailure) : NavVoiceState
    data object NoAppAvailable : NavVoiceState
    data class AmbiguousQuery(val query: String) : NavVoiceState
    data class UnlockPhone(val query: String) : NavVoiceState
}
