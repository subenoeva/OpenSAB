package com.opensab.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The two settings the reply row adds, resolved as pure functions.
 *
 * Both follow the shape of `resolveTemperatureUnit`: what is stored is
 * what the rider chose, and what is *used* is resolved from it at the point of use — so the code
 * always has an answer for a rider who has chosen nothing, and never has to invent one at write
 * time and freeze it.
 */
class ReplySettingsTest {

    private val defaults = listOf("On my way", "Can't talk now", "OK", "Call you later")

    // -------------------------------------------------------------------------------------------
    // The rider's own replies
    // -------------------------------------------------------------------------------------------

    /**
     * An empty list is "the rider has not touched this", exactly as `temperatureUnit = null` is,
     * and it resolves to the four the app ships translated.
     */
    @Test
    fun `falls back to the defaults when the rider has chosen nothing`() {
        assertEquals(defaults, resolveQuickReplies(emptyList(), defaults))
    }

    /**
     * Three replies are three replies. Filling the list up to the length of the defaults would put
     * words in the rider's mouth that he deleted on purpose.
     */
    @Test
    fun `keeps what the rider chose even when it is shorter than the defaults`() {
        val chosen = listOf("Yes", "No", "Later")

        assertEquals(chosen, resolveQuickReplies(chosen, defaults))
    }

    /** Blank entries are not replies: a list of four with one emptied is a list of three. */
    @Test
    fun `drops blank entries rather than offering an empty reply`() {
        val chosen = listOf("Yes", "   ", "", "Later")

        assertEquals(listOf("Yes", "Later"), resolveQuickReplies(chosen, defaults))
    }

    @Test
    fun `reads a list of nothing but blanks as the rider having chosen nothing`() {
        assertEquals(defaults, resolveQuickReplies(listOf("", "  "), defaults))
    }

    // -------------------------------------------------------------------------------------------
    // How long "Sent" holds the screen
    // -------------------------------------------------------------------------------------------

    @Test
    fun `keeps the default linger when the rider has not touched it`() {
        assertEquals(2.seconds, Tunable.REPLY_SENT_LINGER.resolve(AppSettings()))
    }

    @Test
    fun `hands over the seconds the rider asked for`() {
        assertEquals(7.seconds, Tunable.REPLY_SENT_LINGER.resolve(AppSettings(replySentLingerSeconds = 7)))
    }

    /**
     * Clamped **on read** and never on write. A value from an older
     * version, or from a settings file edited by hand, cannot leave the cluster stuck on "Sent" for
     * the rest of the ride: the range wins over what is stored, every time it is read.
     */
    @Test
    fun `clamps a stored linger that is outside the range`() {
        assertEquals(Tunable.REPLY_SENT_LINGER.range.first.seconds, Tunable.REPLY_SENT_LINGER.resolve(AppSettings(replySentLingerSeconds = 0)))
        assertEquals(Tunable.REPLY_SENT_LINGER.range.first.seconds, Tunable.REPLY_SENT_LINGER.resolve(AppSettings(replySentLingerSeconds = -30)))
        assertEquals(Tunable.REPLY_SENT_LINGER.range.last.seconds, Tunable.REPLY_SENT_LINGER.resolve(AppSettings(replySentLingerSeconds = 999)))
    }

    @Test
    fun `leaves both ends of the range alone`() {
        assertEquals(
            Tunable.REPLY_SENT_LINGER.range.first.seconds,
            Tunable.REPLY_SENT_LINGER.resolve(AppSettings(replySentLingerSeconds = Tunable.REPLY_SENT_LINGER.range.first))
        )
        assertEquals(
            Tunable.REPLY_SENT_LINGER.range.last.seconds,
            Tunable.REPLY_SENT_LINGER.resolve(AppSettings(replySentLingerSeconds = Tunable.REPLY_SENT_LINGER.range.last))
        )
    }
}
