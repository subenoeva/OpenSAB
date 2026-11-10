package com.opensab.cluster

/** Effect a page requests on the navigation stack after handling an input. */
sealed interface NavigationAction {

    /** Pushes a subpage on top of the active stack. */
    data class Next(val page: PageProvider) : NavigationAction

    /** Pops the top page and restores the one underneath. */
    data object Pop : NavigationAction

    /** Swaps the page on top without touching the rest of the history. */
    data class Replace(val page: PageProvider) : NavigationAction

    /** Injects a page in the absolute priority stack, used by incoming calls. */
    data class Interrupt(val page: PageProvider) : NavigationAction

    /** Clears every stack and returns to the root page. */
    data object Reset : NavigationAction

    /** Leaves the stack untouched and re-projects the active page. */
    data object Invalidate : NavigationAction

    /** The press was consumed without any visible effect. */
    data object None : NavigationAction
}
