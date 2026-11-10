package com.opensab.cluster

import com.opensab.domain.speech.SpeechRequest
import kotlinx.coroutines.flow.Flow

/**
 * A page that says something out loud while it is mounted.
 *
 * The page names the phrases; it does not speak them. The voice has one owner and it is
 * [BaseStateManager], because it is the only thing here that knows the instant a page stops being
 * mounted — and that instant is the whole contract: leaving the page cuts the sentence in half,
 * which is a normal outcome and not a failure.
 *
 * A page has to have nothing to say to skip this interface. Declaring it and emitting nothing is
 * the same as not declaring it, only harder to read.
 *
 * Speaking flows have two distinct semantics:
 *  - State-derived flow: combines active state flows with distinctUntilChanged / sequence numbers
 *    so mounting or repeating the page speaks the active item or header.
 *  - Event-derived flow: emits announcements once on transitions or inputs without replay, so
 *    resuming does not repeat the past action.
 */
interface SpeakingPage : PageProvider {

    /**
     * Scripts this page wants said, one whole utterance list per emission. A new emission replaces
     * whatever is still being said, so a second one is a repeat and never a queue.
     *
     * **Deliberately without replay.** A script already handed over is not handed over again, so
     * coming back from an interruption resumes the page without starting the forecast from the top
     * — which is what `ResumeBehavior.RETAIN` should mean for a voice, and it falls out of the
     * mechanism instead of being remembered by a flag.
     */
    val speech: Flow<List<SpeechRequest>>
}
