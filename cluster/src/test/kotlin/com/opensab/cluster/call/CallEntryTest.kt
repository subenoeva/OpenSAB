package com.opensab.cluster.call

import com.opensab.cluster.StubPageProvider
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallStatus
import com.opensab.sab.protocol.projection.ProjectionFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class CallEntryTest {

    private val live = StubPageProvider("live", ProjectionFrame.RequestSabMode(1))
    private val menu = StubPageProvider("menu", ProjectionFrame.RequestSabMode(2))
    private val activeCall = CallInfo(
        callerName = "Ana",
        callerNumber = "600100100",
        status = CallStatus.ACTIVE
    )

    @Test
    fun `should open the live call page while there is a call`() {
        val page = callEntryPage(
            activeCall = activeCall,
            livePage = { live },
            menuPage = { menu }
        )
        assertEquals(live, page)
    }

    @Test
    fun `should open the two-row menu when there is none`() {
        val page = callEntryPage(
            activeCall = null,
            livePage = { live },
            menuPage = { menu }
        )
        assertEquals(menu, page)
    }

    @Test
    fun `should build only the page it opens`() {
        var liveBuilds = 0
        var menuBuilds = 0

        val page = callEntryPage(
            activeCall = null,
            livePage = { liveBuilds++; live },
            menuPage = { menuBuilds++; menu }
        )

        assertEquals(menu, page)
        assertEquals(0, liveBuilds)
        assertEquals(1, menuBuilds)
    }
}
