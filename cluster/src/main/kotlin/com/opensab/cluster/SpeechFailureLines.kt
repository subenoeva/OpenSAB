package com.opensab.cluster

import com.opensab.cluster.text.ClusterToken
import com.opensab.domain.speech.RecognitionFailure
import com.opensab.domain.speech.SpeechToken

/**
 * One cluster line per reason a listen can fail, shared by every page that listens.
 *
 * One line per reason and not a generic text, because the rider does something different about each:
 * a denied microphone is a trip to Settings, no network is a wait, and a busy recogniser is a retry.
 *
 * They are cluster copy, so they are tokens and the page resolves them. One shared table rather than
 * one per page: two tables would drift the day somebody improves one line, and these are the words a
 * rider reads at speed to decide what to do.
 */
internal fun clusterLineFor(reason: RecognitionFailure): ClusterToken = when (reason) {
    RecognitionFailure.NO_PERMISSION -> ClusterToken.LISTEN_NO_PERMISSION
    RecognitionFailure.NO_RECOGNIZER -> ClusterToken.LISTEN_NO_RECOGNIZER
    RecognitionFailure.NETWORK -> ClusterToken.LISTEN_NETWORK
    RecognitionFailure.AUDIO -> ClusterToken.LISTEN_AUDIO
    RecognitionFailure.SERVER -> ClusterToken.LISTEN_SERVER
    RecognitionFailure.CLIENT -> ClusterToken.LISTEN_CLIENT
    RecognitionFailure.BUSY -> ClusterToken.LISTEN_BUSY
    RecognitionFailure.LANGUAGE -> ClusterToken.LISTEN_LANGUAGE
    RecognitionFailure.UNKNOWN -> ClusterToken.LISTEN_UNKNOWN
    RecognitionFailure.MIC_NEEDS_APP -> ClusterToken.LISTEN_OPEN_APP
}

/**
 * What the voice says for a failed listen: the page's own [retry] phrase, except when the
 * microphone needs the app opened, where retrying from the handlebar can never work.
 */
internal fun spokenFailureFor(reason: RecognitionFailure, retry: SpeechToken): SpeechToken =
    if (reason == RecognitionFailure.MIC_NEEDS_APP) SpeechToken.VOICE_MIC_NEEDS_APP else retry
