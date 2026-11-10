package com.opensab.domain.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatcherTest {

    private fun contact(
        id: Long,
        name: String,
        starred: Boolean = false,
        vararg numbers: PhoneNumber
    ) = Contact(id, name, starred, numbers.toList())

    private fun mobile(value: String, primary: Boolean = false) =
        PhoneNumber(value, PhoneKind.MOBILE, primary)

    @Test
    fun `should match a name typed with accents against one without`() {
        val found = listOf(contact(1, "Anna Ruíz", numbers = arrayOf(mobile("600100100"))))

        val targets = ContactMatcher.rank("anna ruiz", found)

        assertEquals(listOf("Anna Ruíz"), targets.map { it.displayName })
    }

    @Test
    fun `should drop a courtesy prefix before matching`() {
        assertEquals("ana", ContactMatcher.queryFor("Llama a Ana"))
        assertEquals("ana", ContactMatcher.queryFor("llamar a ana"))
        assertEquals("ana", ContactMatcher.queryFor("call Ana"))
    }

    @Test
    fun `should keep a bare name that looks like a courtesy word`() {
        // Nothing behind the prefix means it was not a prefix: it was the whole thing said.
        assertEquals("call", ContactMatcher.queryFor("call"))
        assertEquals("llama", ContactMatcher.queryFor("Llama"))
    }

    @Test
    fun `should rank an exact full name first`() {
        val found = listOf(
            // "Abel Ruiz Anaya" is tier 1 (both words said prefix a word of it — "ana" prefixes
            // "anaya") and sorts before "Ana Ruiz" alphabetically, so the assertion below can only
            // pass through the exact-match tier winning, not through the alphabetical fallback.
            contact(1, "Abel Ruiz Anaya", numbers = arrayOf(mobile("600100100"))),
            contact(2, "Ana Ruiz", numbers = arrayOf(mobile("600200200")))
        )

        val targets = ContactMatcher.rank("Ana Ruiz", found)

        assertEquals(listOf("Ana Ruiz", "Abel Ruiz Anaya"), targets.map { it.displayName })
    }

    @Test
    fun `should rank all-word prefixes above some-word prefixes`() {
        val found = listOf(
            // "Ana Perez" is tier 2 (only "ana" matches; "ru" matches nothing) and sorts before
            // "Ana Ruiz" alphabetically, so the assertion below can only pass through the tier
            // split, not through the alphabetical fallback.
            contact(1, "Ana Perez", numbers = arrayOf(mobile("600100100"))),
            contact(2, "Ana Ruiz", numbers = arrayOf(mobile("600200200")))
        )

        // Both words of "ana ru" prefix a word of "Ana Ruiz", but only "ana" prefixes a word of
        // "Ana Perez" — the loose "ru" matches nothing there.
        val targets = ContactMatcher.rank("ana ru", found)

        assertEquals(listOf("Ana Ruiz", "Ana Perez"), targets.map { it.displayName })
    }

    @Test
    fun `should prefer a starred contact at the same quality`() {
        val found = listOf(
            // The star sits on "Ana Torres", which sorts second alphabetically, so the assertion
            // below can only pass through the starred key, not through the alphabetical fallback.
            contact(1, "Ana Ruiz", numbers = arrayOf(mobile("600100100"))),
            contact(2, "Ana Torres", starred = true, numbers = arrayOf(mobile("600200200")))
        )

        val targets = ContactMatcher.rank("ana", found)

        assertEquals(listOf("Ana Torres", "Ana Ruiz"), targets.map { it.displayName })
    }

    @Test
    fun `should fall back to alphabetical order`() {
        val found = listOf(
            contact(1, "Torres Gomez", numbers = arrayOf(mobile("600100100"))),
            contact(2, "Sanz Gomez", numbers = arrayOf(mobile("600200200"))),
            contact(3, "Ruiz Gomez", numbers = arrayOf(mobile("600300300")))
        )

        // All three tie on quality and none is starred, so the same tie is always broken the
        // same way — alphabetically — rather than by whatever order the provider handed them in.
        val targets = ContactMatcher.rank("gomez", found)

        assertEquals(listOf("Ruiz Gomez", "Sanz Gomez", "Torres Gomez"), targets.map { it.displayName })
    }

    @Test
    fun `should give the same list for the same input every time`() {
        val found = listOf(
            contact(1, "Ana Torres", numbers = arrayOf(mobile("600100100"))),
            contact(2, "Ana Ruiz", numbers = arrayOf(mobile("600200200"))),
            contact(3, "Ana Sanz", numbers = arrayOf(mobile("600300300")))
        )

        val runs = (1..50).map { ContactMatcher.rank("ana", found) }

        assertTrue("the ranking is not reproducible", runs.all { it == runs.first() })
        assertEquals(listOf("Ana Ruiz", "Ana Sanz", "Ana Torres"), runs.first().map { it.displayName })
    }

    @Test
    fun `should flatten a contact with three numbers into three targets`() {
        val found = listOf(
            contact(
                1,
                "Ana Ruiz",
                numbers = arrayOf(
                    mobile("600100100"),
                    PhoneNumber("600200200", PhoneKind.HOME),
                    PhoneNumber("600300300", PhoneKind.WORK)
                )
            )
        )

        val targets = ContactMatcher.rank("ana ruiz", found)

        assertEquals(3, targets.size)
        assertTrue(targets.all { it.displayName == "Ana Ruiz" })
    }

    @Test
    fun `should keep the address book order of the numbers`() {
        val found = listOf(
            contact(
                1,
                "Ana Ruiz",
                numbers = arrayOf(
                    PhoneNumber("600100100", PhoneKind.HOME),
                    PhoneNumber("600200200", PhoneKind.MOBILE, isPrimary = true),
                    PhoneNumber("600300300", PhoneKind.WORK)
                )
            )
        )

        // The primary number jumps to the front; the other two keep their original relative order.
        val targets = ContactMatcher.rank("ana ruiz", found)

        assertEquals(listOf("600200200", "600100100", "600300300"), targets.map { it.number })
    }

    @Test
    fun `should put the default number of a contact first`() {
        val found = listOf(
            contact(
                1,
                "Ana Ruiz",
                numbers = arrayOf(
                    PhoneNumber("600100100", PhoneKind.HOME),
                    PhoneNumber("600200200", PhoneKind.MOBILE, isPrimary = true)
                )
            )
        )

        val targets = ContactMatcher.rank("ana", found)

        assertEquals(listOf("600200200", "600100100"), targets.map { it.number })
    }

    @Test
    fun `should never rank more than five targets`() {
        val found = (1..4L).map { id ->
            contact(
                id,
                "Ana $id",
                numbers = arrayOf(mobile("60010010$id"), mobile("60020020$id"))
            )
        }

        // The literal and not the constant: `assertEquals(MAX_TARGETS, ...)` against eight
        // available targets stays green if somebody raises the cap to eight, and the cap is five.
        // The constant is asserted separately so the two cannot drift apart in silence.
        assertEquals(5, ContactMatcher.rank("ana", found).size)
        assertEquals(5, ContactMatcher.MAX_TARGETS)
    }

    @Test
    fun `should never cut the favourites off`() {
        val found = (1..4L).map { id ->
            contact(
                id,
                "Ana $id",
                numbers = arrayOf(mobile("60010010$id"), mobile("60020020$id"))
            )
        }

        // The cap is the ranking's, not the address book's: there is no "speak again" out of a
        // favourite that was dropped off the end.
        assertEquals(8, ContactMatcher.favorites(found).size)
    }

    @Test
    fun `should return nothing when no contact matches`() {
        val found = listOf(contact(1, "Ana Ruiz", numbers = arrayOf(mobile("600100100"))))

        val targets = ContactMatcher.rank("zzz", found)

        assertEquals(emptyList<CallTarget>(), targets)
    }

    @Test
    fun `should order the favourites alphabetically`() {
        val found = listOf(
            contact(1, "Torres", starred = true, numbers = arrayOf(mobile("600100100"))),
            contact(2, "Ana", starred = true, numbers = arrayOf(mobile("600200200"))),
            contact(3, "Beto", starred = true, numbers = arrayOf(mobile("600300300")))
        )

        val targets = ContactMatcher.favorites(found)

        assertEquals(listOf("Ana", "Beto", "Torres"), targets.map { it.displayName })
    }

    @Test
    fun `should strip a courtesy prefix idempotently`() {
        val once = ContactMatcher.queryFor("Llama a Ana")
        val twice = ContactMatcher.queryFor(once)

        assertEquals(once, twice)
    }
}
