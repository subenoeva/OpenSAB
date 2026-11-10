package com.opensab.cluster.message

import com.opensab.cluster.StubPageProvider
import com.opensab.domain.message.MessageInfo
import com.opensab.sab.protocol.projection.ProjectionFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MessageEntryTest {

    private val readPage = StubPageProvider("ReadPage", ProjectionFrame.RequestSabMode(1))
    private val menuPage = StubPageProvider("MenuPage", ProjectionFrame.RequestSabMode(2))

    @Test
    fun returnsReadPageWhenCurrentMessageIsNotNull() {
        val message = MessageInfo(sender = "Elena", body = "Are you far?")
        val result = messageEntryPage(
            currentMessage = message,
            readPage = { readPage },
            menuPage = { menuPage }
        )
        assertSame(readPage, result)
    }

    @Test
    fun returnsMenuPageWhenCurrentMessageIsNull() {
        val result = messageEntryPage(
            currentMessage = null,
            readPage = { readPage },
            menuPage = { menuPage }
        )
        assertSame(menuPage, result)
    }

    @Test
    fun buildsOnlyThePageItOpensWhenMessageIsPresent() {
        var readBuilt = 0
        var menuBuilt = 0
        val message = MessageInfo(sender = "Elena", body = "Are you far?")

        messageEntryPage(
            currentMessage = message,
            readPage = { readBuilt++; readPage },
            menuPage = { menuBuilt++; menuPage }
        )

        assertEquals(1, readBuilt)
        assertEquals(0, menuBuilt)
    }

    @Test
    fun buildsOnlyThePageItOpensWhenMessageIsNull() {
        var readBuilt = 0
        var menuBuilt = 0

        messageEntryPage(
            currentMessage = null,
            readPage = { readBuilt++; readPage },
            menuPage = { menuBuilt++; menuPage }
        )

        assertEquals(0, readBuilt)
        assertEquals(1, menuBuilt)
    }
}
