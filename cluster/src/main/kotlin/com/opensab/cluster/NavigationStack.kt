package com.opensab.cluster

/**
 * Double navigation stack: a default stack holding the browsing history and an interrupt stack of
 * absolute priority holding modal pages such as an incoming call.
 */
class NavigationStack<T : Any> {

    private val defaultStack = ArrayDeque<T>()
    private val interruptStack = ArrayDeque<T>()
    private val lock = Any()

    /** Total number of pages held across both stacks. */
    val size: Int
        get() = synchronized(lock) { defaultStack.size + interruptStack.size }

    val isEmpty: Boolean
        get() = size == 0

    val hasInterrupt: Boolean
        get() = synchronized(lock) { interruptStack.isNotEmpty() }

    /**
     * Whether the default stack holds a root page. An interruption does not count as one.
     *
     * The distinction matters because [isEmpty] counts both stacks: a call already claiming the
     * screen made the engine believe there was something to fall back to when there was not.
     */
    val hasRoot: Boolean
        get() = synchronized(lock) { defaultStack.isNotEmpty() }

    /** Pushes onto the interrupt stack while an interruption is active, onto the default one otherwise. */
    fun push(item: T) {
        synchronized(lock) {
            activeStack().addLast(item)
        }
    }

    fun peek(): T? = synchronized(lock) { activeStack().lastOrNull() }

    /**
     * The whole stack, bottom to top, resolved in the same order as [peek]: the default stack first
     * and the interrupt stack after, so the last element is always the page [peek] hands over.
     *
     * A copy, not a live view: the mirror publishes the trail into a `StateFlow` that outlives the
     * call, and a view would keep mutating under the UI.
     */
    fun snapshot(): List<T> = synchronized(lock) { defaultStack + interruptStack }

    /**
     * Pops the top page. The root of the default stack is never popped: there is nothing to fall
     * back to, and an empty stack would leave the cluster with no frame to project.
     */
    fun pop(): T? = synchronized(lock) {
        if (interruptStack.isNotEmpty()) {
            return interruptStack.removeLast()
        }
        if (defaultStack.size <= 1) {
            return null
        }
        defaultStack.removeLast()
    }

    /** Replaces the top page and returns the one it displaced. */
    fun replace(item: T): T? = synchronized(lock) {
        val stack = activeStack()
        val previous = stack.removeLastOrNull()
        stack.addLast(item)
        previous
    }

    /**
     * Installs the root of the default stack, bypassing the interrupt stack [push] would choose.
     *
     * A plain `push` writes to whichever stack is active, so with an interruption already up the
     * root would land *on top of* the call instead of underneath it. A no-op when a root is already
     * there: this establishes the floor, it does not replace it.
     */
    fun installRoot(item: T) {
        synchronized(lock) {
            if (defaultStack.isEmpty()) defaultStack.addLast(item)
        }
    }

    /** Injects a page in the absolute priority stack. */
    fun interrupt(item: T) {
        synchronized(lock) {
            interruptStack.addLast(item)
        }
    }

    /** Drops the whole interrupt stack and hands control back to the default one. */
    fun clearInterrupt() {
        synchronized(lock) {
            interruptStack.clear()
        }
    }

    /** Clears the interruptions and unwinds the default stack down to its root. */
    fun resetToRoot(): T? = synchronized(lock) {
        interruptStack.clear()
        while (defaultStack.size > 1) {
            defaultStack.removeLast()
        }
        defaultStack.lastOrNull()
    }

    fun clear() {
        synchronized(lock) {
            interruptStack.clear()
            defaultStack.clear()
        }
    }

    private fun activeStack(): ArrayDeque<T> =
        interruptStack.ifEmpty { defaultStack }
}
