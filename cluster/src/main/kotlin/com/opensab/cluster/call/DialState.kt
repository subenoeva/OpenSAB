package com.opensab.cluster.call

import com.opensab.cluster.message.MessageReplyPageProvider
import com.opensab.domain.contacts.CallTarget
import com.opensab.domain.speech.RecognitionFailure

/**
 * How the dialler was opened, and the only thing the two rows of the call menu differ in.
 *
 * `Favorites` carries the targets rather than a flag because building a page observes nothing: the
 * menu already read the address book to know whether its row opens, so the list travels with the
 * press instead of being read a second time from a constructor that is not allowed to read anything.
 */
sealed interface DialSeed {
    data object Voice : DialSeed
    data class Favorites(val targets: List<CallTarget>) : DialSeed
}

/**
 * The faces of [CallDialPageProvider], rendered from one provider for the reason
 * [MessageReplyPageProvider] spells out: this engine has no asynchronous navigation, so eight pages
 * would each have to push the next one, and that is a second owner of the stack.
 *
 * A `sealed interface` and not an enum because four of them carry the thing they are about: the
 * candidates being chosen among, the name that matched nobody, the reason a listen failed, and the
 * target of a call that is going or did not go.
 *
 * **There is no `Placed`.** The instant the call is with the system the page replaces itself with
 * the live call page, because from then on what the rider needs is to hang up, not to dial.
 */
sealed interface DialState {

    /** The microphone is open. */
    data object Listening : DialState

    /**
     * The candidates, with the cursor on [index]. `@Select` calls **the one being looked at**.
     *
     * One face for two problems: two contacts called Ana and one contact with three numbers are the
     * same list, because [CallTarget] flattened them the same way.
     */
    data class Confirming(val targets: List<CallTarget>, val index: Int) : DialState

    /** The microphone opened and nothing came out. Not a failure. */
    data object NothingUnderstood : DialState

    /**
     * Something was understood and the address book has nobody like it. [heard] is kept for the log:
     * the cluster line is a constant, and knowing what was actually heard is the only way to tell a
     * bad listen from a bad match when reading a capture.
     */
    data class NoMatch(val heard: String) : DialState

    /** The listen failed for a reason the rider can act on. */
    data class Failed(val reason: RecognitionFailure) : DialState

    /**
     * The call is being placed. **No exit and no cancel of its own:** pressing "call" twice is how a
     * rider calls twice, and there is no cancelling because the intent is already with the system.
     */
    data class Placing(val target: CallTarget) : DialState

    /** `place()` came back false, and [target] is kept so `@Back` retries the very same call. */
    data class NotPlaced(val target: CallTarget) : DialState
}
