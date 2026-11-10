package com.opensab.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The double stack seen whole rather than from the top.
 *
 * The navigation trail needs the entire stack, and `peek` only ever hands over one page. `snapshot` resolves in the same order `peek` does — default stack
 * first, interrupt stack after — so the trail reads bottom to top exactly as the engine unwinds it.
 */
class NavigationStackTest {

    @Test
    fun `snapshot of an empty stack is empty`() {
        assertEquals(emptyList<String>(), NavigationStack<String>().snapshot())
    }

    @Test
    fun `snapshot returns the default stack from root to top`() {
        val stack = NavigationStack<String>()
        stack.push("RootMenu")
        stack.push("MusicPage")
        stack.push("VolumePage")

        assertEquals(listOf("RootMenu", "MusicPage", "VolumePage"), stack.snapshot())
    }

    @Test
    fun `snapshot puts the interrupt stack after the default one, as peek resolves it`() {
        val stack = NavigationStack<String>()
        stack.push("RootMenu")
        stack.push("MusicPage")
        stack.interrupt("CallPage")

        assertEquals(listOf("RootMenu", "MusicPage", "CallPage"), stack.snapshot())
        assertEquals("The last element of the snapshot is what peek resolves", "CallPage", stack.peek())
    }

    @Test
    fun `snapshot is a copy and not a live view of the stack`() {
        val stack = NavigationStack<String>()
        stack.push("RootMenu")
        val snapshot = stack.snapshot()

        stack.push("MusicPage")

        assertEquals(listOf("RootMenu"), snapshot)
        assertEquals(listOf("RootMenu", "MusicPage"), stack.snapshot())
    }

    @Test
    fun `snapshot drops the interruption once it is cleared`() {
        val stack = NavigationStack<String>()
        stack.push("RootMenu")
        stack.interrupt("CallPage")

        stack.clearInterrupt()

        assertEquals(listOf("RootMenu"), stack.snapshot())
    }

    /**
     * `push` writes to whichever stack is active, so with an interruption up it would stack the
     * root on top of the call. `installRoot` establishes the floor of the default stack instead,
     * which is what an engine entering SAB_MODE with a call already ringing needs.
     */
    @Test
    fun `installRoot lands under an interruption, not on top of it`() {
        val stack = NavigationStack<String>()
        stack.interrupt("CallPage")

        stack.installRoot("RootMenu")

        assertEquals(listOf("RootMenu", "CallPage"), stack.snapshot())
        assertEquals("The call still owns the screen", "CallPage", stack.peek())
        assertTrue("And there is now something to fall back to", stack.hasRoot)
    }

    @Test
    fun `should replace the top of the default stack`() {
        val stack = NavigationStack<String>()
        stack.installRoot("root")
        stack.push("dialler")

        stack.replace("live call")

        assertEquals(listOf("root", "live call"), stack.snapshot())
        assertEquals("The size does not change when swapping the top", 2, stack.size)
        assertEquals("The root underneath remains untouched", "live call", stack.peek())
    }

    @Test
    fun `should replace the top of the interrupt stack while one is up`() {
        val stack = NavigationStack<String>()
        stack.installRoot("root")
        stack.push("dialler")
        stack.interrupt("incoming call")

        val displaced = stack.replace("live call")

        // replace() writes to the *active* stack, exactly like pop(): with an interruption up it
        // swaps the interruption and leaves the default stack untouched (NavigationStack:120).
        assertEquals("incoming call", displaced)
        assertEquals(listOf("root", "dialler", "live call"), stack.snapshot())
    }

    @Test
    fun `should hand back the page it displaced`() {
        val stack = NavigationStack<String>()
        stack.installRoot("root")
        stack.push("dialler")

        val displaced = stack.replace("live call")

        assertEquals("dialler", displaced)
    }
}

