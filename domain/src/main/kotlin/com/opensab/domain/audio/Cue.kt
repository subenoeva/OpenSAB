package com.opensab.domain.audio

/**
 * The eight things the cluster can say without a screen, in nine entries: [LINK_UP] and
 * [LINK_DOWN] are the two faces of one cue.
 *
 * Eight and not more, and the cut is the design: every extra tone is one more place to ask "and
 * here too?", and a list nobody can recite is a list nobody tells apart with a helmet on.
 */
enum class Cue {
    /** The cursor moved, in the carousel or in any list. */
    MOVE,

    /** The cursor hit the end of a list, or the volume hit its own. */
    EDGE,

    /** A page was entered. The engine's, off `NavigationAction.Next`. */
    ENTER,

    /** A page was left. The engine's, off `NavigationAction.Pop`. */
    LEAVE,

    /** The action went: the call is away, the reply is sent, the route started. */
    SUCCESS,

    /** The action failed, or the search came back with nothing. */
    FAILURE,

    /** The microphone just opened: speak now. */
    LISTEN,

    /** The motorcycle is linked. Emitted by the orchestrator, never by the engine. */
    LINK_UP,

    /** The motorcycle is gone. Emitted from the same place as [LINK_UP]. */
    LINK_DOWN
}
