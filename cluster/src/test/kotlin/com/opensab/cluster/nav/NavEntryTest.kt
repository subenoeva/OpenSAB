package com.opensab.cluster.nav

import com.opensab.cluster.StubPageProvider
import com.opensab.domain.nav.ManeuverType
import com.opensab.domain.nav.NavInstruction
import com.opensab.sab.protocol.projection.ProjectionFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class NavEntryTest {

    private val live = StubPageProvider("live", ProjectionFrame.RequestSabMode(1))
    private val menu = StubPageProvider("menu", ProjectionFrame.RequestSabMode(2))
    private val activeInstruction = NavInstruction(
        title = "In 200m",
        streetName = "Gran Vía",
        distanceFormatted = "200m",
        maneuver = ManeuverType.TURN_RIGHT
    )

    @Test
    fun `should open live navigation page while there is active guidance`() {
        val page = navEntryPage(
            navInstruction = activeInstruction,
            livePage = { live },
            menuPage = { menu }
        )
        assertEquals(live, page)
    }

    @Test
    fun `should open navigation menu when there is no active guidance`() {
        val page = navEntryPage(
            navInstruction = null,
            livePage = { live },
            menuPage = { menu }
        )
        assertEquals(menu, page)
    }

    @Test
    fun `should build only the page it opens`() {
        var liveBuilds = 0
        var menuBuilds = 0

        val page = navEntryPage(
            navInstruction = null,
            livePage = { liveBuilds++; live },
            menuPage = { menuBuilds++; menu }
        )

        assertEquals(menu, page)
        assertEquals(0, liveBuilds)
        assertEquals(1, menuBuilds)
    }
}
