package com.opensab.app.logging

import com.opensab.logging.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.Locale

class TimberLogSinkTest {

    private data class Line(val priority: Int, val tag: String?, val message: String, val t: Throwable?)

    private class RecordingTree : FacadeAwareDebugTree() {
        val lines = mutableListOf<Line>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines += Line(priority, tag, message, t)
        }
    }

    private val tree = RecordingTree()
    private var verbose = true
    private val sink = TimberLogSink { verbose }

    @Before
    fun plant() {
        Timber.plant(tree)
        Log.install(sink)
    }

    @After
    fun uproot() {
        Log.uninstall(sink)
        Timber.uproot(tree)
    }

    /** Runs [call] straight through Timber, then through the facade, and returns both lines. */
    private fun bothWays(call: (viaFacade: Boolean) -> Unit): Pair<Line, Line> {
        call(false)
        call(true)
        assertEquals("expected one line per call: ${tree.lines}", 2, tree.lines.size)
        return tree.lines[0] to tree.lines[1]
    }

    @Test
    fun verboseLinesAreDroppedWhileTheSettingIsOff() {
        verbose = false
        Log.v("Transmitting base screen frame [3 bytes]: 01 02 03")
        Log.d("DashboardMode changed to: SAB_MODE")
        assertEquals(listOf("DashboardMode changed to: SAB_MODE"), tree.lines.map { it.message })
    }

    @Test
    fun verboseLinesPassWhileTheSettingIsOn() {
        verbose = true
        Log.v("Transmitting base screen frame [3 bytes]: 01 02 03")
        assertEquals(android.util.Log.VERBOSE, tree.lines.single().priority)
    }

    @Test
    fun facadePrioritiesAreAndroidLogPriorities() {
        assertEquals(android.util.Log.VERBOSE, Log.VERBOSE)
        assertEquals(android.util.Log.DEBUG, Log.DEBUG)
        assertEquals(android.util.Log.INFO, Log.INFO)
        assertEquals(android.util.Log.WARN, Log.WARN)
        assertEquals(android.util.Log.ERROR, Log.ERROR)
    }

    @Test
    fun aLineFromAMethodKeepsItsTimberTagAndMessage() {
        val (timber, facade) = bothWays { Probe().method(it, null, "Could not project page %s", "MUSIC") }
        assertEquals(timber, facade)
        assertEquals(Line(Log.WARN, "Probe", "Could not project page MUSIC", null), facade)
    }

    @Test
    fun aLineFromAnAnonymousObjectKeepsTheMethodInItsTag() {
        val (timber, facade) = bothWays { Probe().anon(it) }
        assertEquals(timber, facade)
        assertEquals("Probe\$anon", facade.tag)
    }

    @Test
    fun aLineFromATopLevelFunctionKeepsTheFileClassTag() {
        val (timber, facade) = bothWays { topLevel(it) }
        assertEquals(timber, facade)
        assertEquals("ProbeKt", facade.tag)
    }

    @Test
    fun formatArgumentsAreAppliedWithTheDefaultLocaleAsTimberDoes() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        try {
            val (timber, facade) = bothWays {
                Probe().method(it, null, "Displacement %f km, mask 0x%02X", 1.5, 10)
            }
            assertEquals(timber, facade)
            assertEquals("Displacement 1,500000 km, mask 0x0A", facade.message)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun aPercentSignWithoutArgumentsIsLeftAlone() {
        val (timber, facade) = bothWays { Probe().method(it, null, "payload 100% [%02X]") }
        assertEquals(timber, facade)
        assertEquals("payload 100% [%02X]", facade.message)
    }

    @Test
    fun aThrowableEndsUpInTheMessageAsTimberPutsIt() {
        val error = IllegalStateException("boom")
        val (timber, facade) = bothWays { Probe().method(it, error, "Frame write failed") }
        assertEquals(timber, facade)
        assertTrue(facade.message, facade.message.startsWith("Frame write failed\njava.lang.IllegalStateException: boom"))
    }

    @Test
    fun aNullMessageWithoutThrowableIsSwallowedAsTimberDoes() {
        Probe().method(false, null, null)
        Probe().method(true, null, null)
        assertEquals(emptyList<Line>(), tree.lines)
    }

    /** `FileLoggingTree` is a plain tree: it must keep getting no tag and writing `[X/OpenSAB]`. */
    @Test
    fun aTreeThatTagsNothingStillReceivesNoTag() {
        val tags = mutableListOf<String?>()
        val plain = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                tags += tag
            }
        }
        Timber.plant(plain)
        try {
            Probe().method(true, null, "to the session file")
        } finally {
            Timber.uproot(plain)
        }
        assertEquals(listOf<String?>(null), tags)
    }

    /** The UI keeps calling Timber directly: its tags must be what a plain `DebugTree` gives. */
    @Test
    fun aDirectTimberCallKeepsThePlainDebugTreeTag() {
        val plainTags = mutableListOf<String?>()
        val plain = object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                plainTags += tag
            }
        }
        Timber.plant(plain)
        try {
            Probe().method(false, null, "from the UI")
        } finally {
            Timber.uproot(plain)
        }
        assertEquals(listOf<String?>("Probe"), plainTags)
        assertEquals("Probe", tree.lines.single().tag)
    }
}
