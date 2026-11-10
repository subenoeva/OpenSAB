package com.opensab.domain.speech

import java.util.Locale

/**
 * One utterance at a time, and the owner of the voice is the page that asked for it: it knows when
 * the utterance starts and ends, and the synthesizer does not.
 *
 * That single owner is why the port is this thin. Audio focus belongs to the same page for the same
 * reason — it starts and stops with the utterance, and only the caller knows when that is — so
 * nothing in this layer asks for it.
 */
interface SpeechSynthesizer {

    /**
     * Suspends until done. Cancelling the coroutine stops the voice, and that is a normal outcome,
     * not a failure.
     *
     * Never throws for a speech problem: a missing engine or an undownloaded language returns
     * immediately (see [TtsAvailability]). The frame is already on the cluster.
     *
     * A null [locale] is the system language.
     */
    suspend fun speak(text: String, locale: Locale? = null)

    /**
     * Cuts the current utterance. Does not release the engine: re-initializing costs seconds and
     * the bike is moving.
     */
    fun stop()
}
