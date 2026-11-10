package com.opensab.domain.speech

import com.opensab.domain.settings.VoiceVerbosity

/**
 * Turns a named phrase into the words the rider hears.
 *
 * It exists because `com.opensab.cluster` sees neither Android nor `R`, and speaking needs a
 * localized string. So the engine says *which* phrase with a [SpeechRequest] and something below
 * the seam says it in a language: the implementation is the only thing in the engine's path that
 * sees `R`, and the table it reads is the only file of this package that names it
 * (`SpeechPhrases` (`:app`)). Everything above this line stays testable on the JVM.
 *
 * A `fun interface` because there is one question to answer and no state to keep: a page under
 * test hands over a lambda, and only the app hands over the resource-backed implementation.
 */
fun interface SpeechTextResolver {

    /** In the rider's language, with [SpeechRequest.args] already substituted. */
    fun resolve(request: SpeechRequest): String

    fun resolve(request: SpeechRequest, verbosity: VoiceVerbosity): String = resolve(request)
}
