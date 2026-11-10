package com.opensab.app.logging

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tree no longer owns a file, so what is left to prove is what it hands the journal.
 *
 * Every `null` tag is cast because Timber.Tree exposes two other public `log` overloads
 * (message+vararg, throwable+message+vararg) that a bare `null` would resolve to ambiguously.
 */
class FileLoggingTreeTest {

    private val received = mutableListOf<Triple<Char, String, String>>()
    private val tree = FileLoggingTree { p, tag, msg -> received += Triple(p, tag, msg) }

    @Test
    fun aLineReachesTheJournalWithItsPriorityAndTag() {
        tree.log(Log.INFO, "TestTag", "Hello OpenSAB", null)
        assertEquals(listOf(Triple('I', "TestTag", "Hello OpenSAB")), received)
    }

    @Test
    fun anUntaggedLineIsOpenSab() {
        tree.log(Log.DEBUG, null as String?, "x", null)
        assertEquals("OpenSAB", received.single().second)
    }

    @Test
    fun theThrowableIsNotAddedAgain() {
        // What Timber hands a tree: the message already ends with the stack trace.
        tree.log(Log.WARN, null as String?, "It failed\njava.lang.IllegalStateException: boom", IllegalStateException("boom"))
        assertEquals("It failed\njava.lang.IllegalStateException: boom", received.single().third)
    }

    @Test
    fun everyPriorityHasItsLetter() {
        listOf(Log.VERBOSE, Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR, Log.ASSERT, 42)
            .forEach { tree.log(it, null as String?, "", null) }
        assertEquals("VDIWEA?", received.joinToString("") { it.first.toString() })
    }
}
