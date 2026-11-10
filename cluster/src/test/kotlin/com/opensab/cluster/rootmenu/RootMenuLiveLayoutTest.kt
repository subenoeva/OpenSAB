package com.opensab.cluster.rootmenu

import com.opensab.cluster.NavigationAction
import com.opensab.cluster.StubPageProvider
import com.opensab.cluster.text.englishClusterText
import com.opensab.domain.settings.RootMenuEntry
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The carousel follows the rider's layout while it is on the cluster. */
@OptIn(ExperimentalCoroutinesApi::class)
class RootMenuLiveLayoutTest {

    private val layout = MutableStateFlow(RootMenuEntry.entries.toList())

    private val page = RootMenuPageProvider(
        entries = layout,
        pageFactory = { StubPageProvider(it.title.name, ProjectionFrame.RequestSabMode(1)) },
        copy = englishClusterText
    )

    private fun header() = (page.render(1) as ProjectionFrame.MenuScreen).headerText
    private fun title(entry: RootMenuEntry) = englishClusterText.resolve(rootMenuItemOf(entry).title)

    @Test
    fun `the stored order is the carousel order`() {
        layout.value = listOf(RootMenuEntry.MUSIC, RootMenuEntry.CALL)

        assertEquals(title(RootMenuEntry.MUSIC), header())
        page.onInput(FunctionId.NEXT)
        assertEquals(title(RootMenuEntry.CALL), header())
    }

    @Test
    fun `reordering keeps the cursor on the same entry`() {
        page.onInput(FunctionId.NEXT) // NAVIGATION
        layout.value = listOf(RootMenuEntry.MUSIC, RootMenuEntry.VOLUME, RootMenuEntry.NAVIGATION)

        assertEquals(title(RootMenuEntry.NAVIGATION), header())
        assertEquals(2, page.selectionIndex)
    }

    @Test
    fun `hiding the selected entry lands on whatever took its index`() {
        repeat(2) { page.onInput(FunctionId.NEXT) } // CALL, index 2
        layout.value = RootMenuEntry.entries - RootMenuEntry.CALL

        assertEquals(title(RootMenuEntry.MESSAGE), header())
    }

    @Test
    fun `hiding the selected last entry lands on the new last one`() {
        repeat(5) { page.onInput(FunctionId.NEXT) } // VOLUME
        layout.value = listOf(RootMenuEntry.WEATHER, RootMenuEntry.MUSIC)

        assertEquals(title(RootMenuEntry.MUSIC), header())
        val menu = page.render(1) as ProjectionFrame.MenuScreen
        assertEquals(IconId.IC_NONE, menu.downActionIconId)
    }

    @Test
    fun `an entry reappearing does not steal the cursor`() {
        layout.value = listOf(RootMenuEntry.MUSIC, RootMenuEntry.CALL)
        page.onInput(FunctionId.NEXT) // CALL
        layout.value = RootMenuEntry.entries.toList()

        assertEquals(title(RootMenuEntry.CALL), header())
    }

    @Test
    fun `the arrows follow the new length`() {
        layout.value = listOf(RootMenuEntry.MUSIC)

        val menu = page.render(1) as ProjectionFrame.MenuScreen
        assertEquals(IconId.IC_NONE, menu.upActionIconId)
        assertEquals(IconId.IC_NONE, menu.downActionIconId)
    }

    @Test
    fun `a layout change asks for a repaint`() = runTest {
        var redraws = 0
        backgroundScope.launch { page.invalidations.collect { redraws++ } }
        runCurrent()

        layout.value = listOf(RootMenuEntry.MUSIC)
        runCurrent()

        assertEquals(1, redraws)
    }

    @Test
    fun `SELECT opens the entry under the cursor after a reorder`() {
        layout.value = listOf(RootMenuEntry.VOLUME, RootMenuEntry.MUSIC)

        val action = page.onInput(FunctionId.SELECT) as NavigationAction.Next

        assertEquals(RootMenuItem.VOLUME.title.name, action.page.name)
    }

    @Test
    fun `every entry maps to the item of the same name`() {
        RootMenuEntry.entries.forEach { assertEquals(it.name, rootMenuItemOf(it).name) }
    }
}
