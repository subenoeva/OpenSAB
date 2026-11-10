package com.opensab.domain.audio

import com.opensab.logging.Log
import com.opensab.logging.LogSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuePlayerTest {

    @Test
    fun `should play nothing and throw nothing without a player`() {
        Cue.entries.forEach { NoCuePlayer.play(it) }
    }

    @Test
    fun `should record what it was asked for in order`() {
        val player = MockCuePlayer()

        player.play(Cue.LISTEN)
        player.play(Cue.MOVE)

        assertEquals(listOf(Cue.LISTEN, Cue.MOVE), player.played)
    }

    @Test
    fun `should let a cue through while the setting is on`() {
        val player = MockCuePlayer()

        GatedCuePlayer(player, UnknownCueOutput) { true }.play(Cue.ENTER)

        assertEquals(listOf(Cue.ENTER), player.played)
    }

    @Test
    fun `should swallow every cue while the setting is off`() {
        // From the one place both emitters go through: neither the engine nor the orchestrator
        // knows the setting exists.
        val player = MockCuePlayer()
        val gated = GatedCuePlayer(player, UnknownCueOutput) { false }

        Cue.entries.forEach { gated.play(it) }

        assertEquals(0, player.totalCalls)
    }

    @Test
    fun `should read the setting at the instant of the cue`() {
        // A setting flipped mid-ride governs the next beep, not the next session — the same rule
        // `sentLinger` follows.
        val player = MockCuePlayer()
        var enabled = true
        val gated = GatedCuePlayer(player, UnknownCueOutput) { enabled }

        gated.play(Cue.MOVE)
        enabled = false
        gated.play(Cue.MOVE)

        assertEquals(listOf(Cue.MOVE), player.played)
    }

    @Test
    fun `should never let a broken player stop what it was announcing`() {
        val broken = object : CuePlayer {
            override fun play(cue: Cue): Unit = throw IllegalStateException("no audio path")
        }

        GatedCuePlayer(broken, UnknownCueOutput) { true }.play(Cue.LISTEN)

        assertTrue("a cue that did not sound is not a reason to stop anything", true)
    }

    /**
     * Found in the field: with Do Not Disturb on, the rider heard no link cue and the
     * log could not tell "never played" from "played and not heard". Page cues stay silent in the log:
     * they fire continuously while the rider moves through the carousel.
     */
    @Test
    fun `a link cue that plays is logged`() = withRecordedLog { lines ->
        val gated = GatedCuePlayer(MockCuePlayer(), { "bt-a2dp Helmet Intercom" }) { true }

        gated.play(Cue.LINK_UP)
        gated.play(Cue.MOVE)
        gated.play(Cue.LINK_DOWN)

        assertEquals(
            listOf("Link cue LINK_UP played on bt-a2dp Helmet Intercom", "Link cue LINK_DOWN played on bt-a2dp Helmet Intercom"),
            lines.filter { it.startsWith("Link cue") }
        )
    }

    @Test
    fun `a link cue that is suppressed says why`() = withRecordedLog { lines ->
        val broken = object : CuePlayer {
            override fun play(cue: Cue): Unit = throw IllegalStateException("no audio path")
        }

        GatedCuePlayer(MockCuePlayer(), UnknownCueOutput) { false }.play(Cue.LINK_UP)
        GatedCuePlayer(broken, UnknownCueOutput) { true }.play(Cue.LINK_DOWN)

        assertEquals(
            listOf("Link cue LINK_UP skipped: sound cues are off", "Link cue LINK_DOWN skipped: the player failed"),
            lines.filter { it.startsWith("Link cue") }
        )
    }

    /**
     * `CUE-UNHEARD`, 2026-09-24 20:24: LINK_UP "played" at volume 0/7, muted. The volume is the
     * rider's to set, so the cue is not raised over it: it is skipped, and the log says it was on
     * purpose.
     */
    @Test
    fun `a link cue on a silenced channel is skipped and says so`() = withRecordedLog { lines ->
        val player = MockCuePlayer()
        val silenced = object : CueOutput {
            override fun describe() = "speaker-safe Pixel 7 on the NOTIFICATION channel (volume 0/7, muted=true)"
            override fun isSilenced() = true
        }
        val gated = GatedCuePlayer(player, silenced) { true }

        gated.play(Cue.LINK_UP)
        gated.play(Cue.MOVE)

        assertEquals(listOf(Cue.MOVE), player.played)
        assertEquals(
            listOf(
                "Link cue LINK_UP skipped: the channel is silenced: " +
                    "speaker-safe Pixel 7 on the NOTIFICATION channel (volume 0/7, muted=true)"
            ),
            lines.filter { it.startsWith("Link cue") }
        )
    }

    private fun withRecordedLog(block: (List<String>) -> Unit) {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sink = LogSink { _, _, message, args ->
            lines += if (args.isEmpty()) message.orEmpty() else String.format(message.orEmpty(), *args)
        }
        Log.install(sink)
        try {
            block(lines)
        } finally {
            Log.uninstall(sink)
        }
    }
}
