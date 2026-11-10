package com.opensab.domain.settings

import com.opensab.domain.settings.RootMenuEntry.CALL
import com.opensab.domain.settings.RootMenuEntry.MESSAGE
import com.opensab.domain.settings.RootMenuEntry.MUSIC
import com.opensab.domain.settings.RootMenuEntry.NAVIGATION
import com.opensab.domain.settings.RootMenuEntry.VOLUME
import com.opensab.domain.settings.RootMenuEntry.WEATHER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is stored is names, what is used is resolved rows. */
class RootMenuLayoutTest {

    private fun rows(vararg pairs: Pair<RootMenuEntry, Boolean>) =
        pairs.map { (entry, visible) -> RootMenuRow(entry, visible) }

    private val factory = RootMenuEntry.entries.map { RootMenuRow(it, visible = true) }

    @Test
    fun `nothing stored is the factory order with every entry visible`() {
        assertEquals(factory, resolveRootMenu(emptyList(), emptySet()))
    }

    @Test
    fun `a stored order is kept`() {
        val order = listOf("MUSIC", "CALL", "NAVIGATION", "WEATHER", "MESSAGE", "VOLUME")

        assertEquals(order, resolveRootMenu(order, emptySet()).map { it.entry.name })
    }

    @Test
    fun `hidden names come back invisible and keep their place`() {
        val resolved = resolveRootMenu(emptyList(), setOf("VOLUME", "WEATHER"))

        assertEquals(
            rows(WEATHER to false, NAVIGATION to true, CALL to true, MESSAGE to true, MUSIC to true, VOLUME to false),
            resolved
        )
    }

    /** A name this version does not know is dropped, never a crash that resets everything. */
    @Test
    fun `an unknown name is dropped`() {
        val resolved = resolveRootMenu(listOf("RADIO", "MUSIC"), setOf("RADIO"))

        assertEquals(MUSIC, resolved.first().entry)
        assertEquals(RootMenuEntry.entries.size, resolved.size)
    }

    /** An entry shipped after the rider saved his order appears, at the end, visible. */
    @Test
    fun `an entry missing from the stored order is appended visible`() {
        val resolved = resolveRootMenu(listOf("MUSIC", "CALL"), emptySet())

        assertEquals(listOf(MUSIC, CALL, WEATHER, NAVIGATION, MESSAGE, VOLUME), resolved.map { it.entry })
        assertTrue(resolved.all { it.visible })
    }

    @Test
    fun `an entry missing from the order but hidden stays hidden`() {
        val resolved = resolveRootMenu(listOf("MUSIC"), setOf("VOLUME"))

        assertFalse(resolved.single { it.entry == VOLUME }.visible)
    }

    @Test
    fun `a duplicated name keeps its first place`() {
        val resolved = resolveRootMenu(listOf("CALL", "MUSIC", "CALL"), emptySet())

        assertEquals(listOf(CALL, MUSIC), resolved.take(2).map { it.entry })
        assertEquals(RootMenuEntry.entries.size, resolved.size)
    }

    /** A stored value can be corrupt; the resolved one never is. */
    @Test
    fun `everything hidden resolves with the first entry visible`() {
        val resolved = resolveRootMenu(listOf("CALL", "MUSIC"), RootMenuEntry.entries.map { it.name }.toSet())

        assertEquals(listOf(CALL), resolved.visibleEntries())
    }

    @Test
    fun `no stored combination resolves to zero visible entries`() {
        val names = RootMenuEntry.entries.map { it.name }
        // Every subset of hidden names, over the factory order and its reverse.
        for (mask in 0 until (1 shl names.size)) {
            val hidden = names.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            for (order in listOf(names, names.reversed())) {
                assertTrue(resolveRootMenu(order, hidden).visibleEntries().isNotEmpty())
            }
        }
    }

    @Test
    fun `visible entries keep the order and skip the hidden`() {
        val list = rows(MUSIC to true, CALL to false, WEATHER to true)

        assertEquals(listOf(MUSIC, WEATHER), list.visibleEntries())
    }

    @Test
    fun `moving a row down shifts the ones in between up`() {
        val moved = factory.moved(from = 0, to = 3)

        assertEquals(listOf(NAVIGATION, CALL, MESSAGE, WEATHER, MUSIC, VOLUME), moved.map { it.entry })
    }

    @Test
    fun `moving a row up shifts the ones in between down`() {
        val moved = factory.moved(from = 4, to = 1)

        assertEquals(listOf(WEATHER, MUSIC, NAVIGATION, CALL, MESSAGE, VOLUME), moved.map { it.entry })
    }

    @Test
    fun `moving out of range is a no-op`() {
        assertEquals(factory, factory.moved(from = 0, to = 6))
        assertEquals(factory, factory.moved(from = -1, to = 2))
        assertEquals(factory, factory.moved(from = 2, to = 2))
    }

    @Test
    fun `toggling flips the visibility of that entry only`() {
        val toggled = factory.toggled(CALL)

        assertFalse(toggled.single { it.entry == CALL }.visible)
        assertEquals(5, toggled.visibleEntries().size)
        assertEquals(factory, toggled.toggled(CALL))
    }

    /** From the writing side: the last visible entry cannot be switched off. */
    @Test
    fun `the last visible entry cannot be hidden`() {
        val one = rows(WEATHER to false, MUSIC to true)

        assertFalse(one.canHide(MUSIC))
        assertEquals(one, one.toggled(MUSIC))
    }

    @Test
    fun `writing rows stores names in order and the hidden set`() {
        val rows = rows(MUSIC to true, CALL to false, WEATHER to true, NAVIGATION to true, MESSAGE to true, VOLUME to false)

        val stored = AppSettings().withRootMenu(rows)

        assertEquals(listOf("MUSIC", "CALL", "WEATHER", "NAVIGATION", "MESSAGE", "VOLUME"), stored.rootMenuOrder)
        assertEquals(setOf("CALL", "VOLUME"), stored.rootMenuHidden)
        assertEquals(rows, resolveRootMenu(stored.rootMenuOrder, stored.rootMenuHidden))
    }
}
