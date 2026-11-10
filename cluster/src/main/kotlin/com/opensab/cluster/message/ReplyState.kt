package com.opensab.cluster.message

import com.opensab.domain.speech.RecognitionFailure

/**
 * The faces of [MessageReplyPageProvider], rendered from one provider.
 *
 * A `sealed interface` and not an enum because three of them carry the thing they are about: the
 * transcript being reviewed, the transcript being sent, and the reason a listen failed. A flag plus
 * a nullable string beside it would let the two disagree, and "sending" with no text is not a state
 * this page can be in.
 */
sealed interface ReplyState {

    /** The microphone is open. */
    data object Listening : ReplyState

    /** [text] was understood and is being read back before it goes. */
    data class Confirming(val text: String) : ReplyState

    /**
     * The rider said nothing, or nothing intelligible.
     *
     * A face of its own and not a [Failed] with a reason, because it is not a failure: it is the
     * normal end of a listen where nobody spoke, and the recognizer reports it as a null rather
     * than as an exception for exactly that reason.
     */
    data object NothingUnderstood : ReplyState

    /** The listen failed for a reason the rider can act on. */
    data class Failed(val reason: RecognitionFailure) : ReplyState

    /**
     * The fixed replies, with the cursor on [index]. The only face that renders a `MenuScreen`.
     *
     * [from] is the face `@Back` returns to, and it lives in the state rather than in a field of
     * the page for the reason [NotSent] keeps its text: there is no way to reach this face without
     * the thing it needs to leave it.
     */
    data class Choosing(val index: Int, val from: ReplyState) : ReplyState

    /** [text] is with the messaging app and not yet acknowledged. */
    data class Sending(val text: String) : ReplyState

    /** The app took it. The only face that ends the page's work. */
    data object Sent : ReplyState

    /**
     * The app refused it, and [text] is kept so `@Back` can try the very same words again.
     *
     * Keeping the text here rather than in a field of the page is what makes the retry impossible
     * to get wrong: there is no way to reach this face without the message it is about.
     */
    data class NotSent(val text: String) : ReplyState
}
