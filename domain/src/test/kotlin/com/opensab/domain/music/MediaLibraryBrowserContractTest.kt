package com.opensab.domain.music

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryBrowserContractTest {

    @Test
    fun browseBeforeConnectReturnsEmptyInsteadOfThrowing() = runTest {
        val browser = FakeMediaLibraryBrowser()

        assertTrue(browser.browse("anything").isEmpty())
    }

    @Test
    fun connectReportsRootAndBrowseReturnsItsChildren() = runTest {
        val browser = FakeMediaLibraryBrowser()
        browser.rootId = "ROOT_AUTO"
        browser.children["ROOT_AUTO"] = listOf(
            MediaLibraryNode("MY_COLLECTION", "Colección", null, isPlayable = false, isBrowsable = true)
        )

        val root = browser.connect("com.aspiro.tidal")

        assertEquals("ROOT_AUTO", root)
        assertEquals("com.aspiro.tidal", browser.lastConnectedPackage)
        assertEquals(listOf("MY_COLLECTION"), browser.browse("ROOT_AUTO").map { it.mediaId })
    }

    @Test
    fun connectReturnsNullWhenServiceIsUnavailable() = runTest {
        val browser = FakeMediaLibraryBrowser()
        browser.rootId = null

        assertNull(browser.connect("com.example.nothing"))
    }

    @Test
    fun disconnectClearsTheConnectionSoBrowseGoesEmptyAgain() = runTest {
        val browser = FakeMediaLibraryBrowser()
        browser.rootId = "ROOT_AUTO"
        browser.children["ROOT_AUTO"] =
            listOf(MediaLibraryNode("X", "X", null, isPlayable = true, isBrowsable = false))
        browser.connect("com.aspiro.tidal")

        browser.disconnect()

        assertEquals(1, browser.disconnectCount)
        assertTrue(browser.browse("ROOT_AUTO").isEmpty())
    }
}
