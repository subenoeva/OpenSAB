package com.opensab.domain.message

import com.opensab.domain.contacts.CallTarget
import com.opensab.domain.contacts.Contact
import com.opensab.domain.contacts.MockContactDirectory
import com.opensab.domain.contacts.PhoneKind
import com.opensab.domain.contacts.PhoneNumber
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMessageMenuAccessTest {

    private val ana = Contact(
        id = 1,
        displayName = "Ana Ruiz",
        starred = true,
        numbers = listOf(PhoneNumber("600100100", PhoneKind.MOBILE))
    )

    private val bob = Contact(
        id = 2,
        displayName = "Bob Smith",
        starred = true,
        numbers = listOf(PhoneNumber("600200200", PhoneKind.MOBILE, isPrimary = true))
    )

    private fun access(
        contacts: Boolean = true,
        mic: Boolean = true,
        sms: Boolean = true,
        directory: MockContactDirectory = MockContactDirectory()
    ) = DefaultMessageMenuAccess(
        contactsGranted = { contacts },
        micGranted = { mic },
        smsGranted = { sms },
        directory = directory
    )

    @Test
    fun `should close all gates when contacts permission is missing`() = runTest {
        val directory = MockContactDirectory().apply { favourites = listOf(ana) }

        val gates = access(contacts = false, mic = true, sms = true, directory = directory).gates()

        assertFalse(gates.voice)
        assertTrue(gates.favorites.isEmpty())
        assertEquals(0, directory.starredCalls)
    }

    @Test
    fun `should close all gates when sms permission is missing`() = runTest {
        val directory = MockContactDirectory().apply { favourites = listOf(ana) }

        val gates = access(contacts = true, mic = true, sms = false, directory = directory).gates()

        assertFalse(gates.voice)
        assertTrue(gates.favorites.isEmpty())
        assertEquals(0, directory.starredCalls)
    }

    @Test
    fun `should close voice but keep favorites when microphone is missing`() = runTest {
        val directory = MockContactDirectory().apply { favourites = listOf(ana) }

        val gates = access(contacts = true, mic = false, sms = true, directory = directory).gates()

        assertFalse(gates.voice)
        assertEquals(listOf(CallTarget("Ana Ruiz", "600100100", PhoneKind.MOBILE)), gates.favorites)
        assertEquals(1, directory.starredCalls)
    }

    @Test
    fun `should open voice and empty favorites when all granted but no starred contacts`() = runTest {
        val directory = MockContactDirectory().apply { favourites = emptyList() }

        val gates = access(contacts = true, mic = true, sms = true, directory = directory).gates()

        assertTrue(gates.voice)
        assertTrue(gates.favorites.isEmpty())
        assertEquals(1, directory.starredCalls)
    }

    @Test
    fun `should open voice and populate favorites when all granted with starred contacts`() = runTest {
        val directory = MockContactDirectory().apply { favourites = listOf(bob, ana) }

        val gates = access(contacts = true, mic = true, sms = true, directory = directory).gates()

        assertTrue(gates.voice)
        val expected = listOf(
            CallTarget("Ana Ruiz", "600100100", PhoneKind.MOBILE),
            CallTarget("Bob Smith", "600200200", PhoneKind.MOBILE)
        )
        assertEquals(expected, gates.favorites)
        assertEquals(1, directory.starredCalls)
    }

    @Test
    fun `mock message menu access records calls and returns next gates`() = runTest {
        val mock = MockMessageMenuAccess()
        assertEquals(0, mock.gatesCalls)
        assertEquals(MessageMenuGates.CLOSED, mock.gates())
        assertEquals(1, mock.gatesCalls)

        val custom = MessageMenuGates(
            voice = true,
            favorites = listOf(CallTarget("Ana Ruiz", "600100100", PhoneKind.MOBILE))
        )
        mock.next = custom
        assertEquals(custom, mock.gates())
        assertEquals(2, mock.gatesCalls)
    }
}
