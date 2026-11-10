package com.opensab.domain.contacts

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMenuAccessTest {

    private val ana = Contact(
        id = 1,
        displayName = "Ana Ruiz",
        starred = true,
        numbers = listOf(PhoneNumber("600100100", PhoneKind.MOBILE))
    )

    private fun access(
        contacts: Boolean,
        phone: Boolean,
        directory: MockContactDirectory
    ) = DefaultCallMenuAccess({ contacts }, { phone }, directory)

    @Test
    fun `should close both rows without the contacts permission`() = runTest {
        val directory = MockContactDirectory().apply { favourites = listOf(ana) }

        val gates = access(contacts = false, phone = true, directory = directory).gates()

        assertFalse(gates.voice)
        assertTrue(gates.favorites.isEmpty())
    }

    @Test
    fun `should close both rows without the call permission`() = runTest {
        val directory = MockContactDirectory().apply { favourites = listOf(ana) }

        val gates = access(contacts = true, phone = false, directory = directory).gates()

        assertFalse(gates.voice)
        assertTrue(gates.favorites.isEmpty())
    }

    @Test
    fun `should never read the address book without permission`() = runTest {
        val directory = MockContactDirectory().apply { favourites = listOf(ana) }

        access(contacts = false, phone = true, directory = directory).gates()

        assertEquals("reading it would throw SecurityException", 0, directory.starredCalls)
    }

    @Test
    fun `should open only the voice row when there are no favourites`() = runTest {
        val directory = MockContactDirectory()

        val gates = access(contacts = true, phone = true, directory = directory).gates()

        assertTrue(gates.voice)
        assertTrue(gates.favorites.isEmpty())
    }

    @Test
    fun `should hand the favourites over as targets`() = runTest {
        val directory = MockContactDirectory().apply { favourites = listOf(ana) }

        val gates = access(contacts = true, phone = true, directory = directory).gates()

        assertTrue(gates.voice)
        assertEquals(listOf(CallTarget("Ana Ruiz", "600100100", PhoneKind.MOBILE)), gates.favorites)
    }
}
