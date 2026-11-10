package com.opensab.domain.contacts

import java.text.Normalizer

/**
 * What was heard, ranked against what the address book gave back. Pure, deterministic, and the whole
 * decision path of dialling by voice.
 *
 * Three steps: normalise both sides the same way, drop a courtesy prefix, and sort with a three-part
 * key. The third part is alphabetical order and it is not decoration — it is what makes the list
 * **reproducible**, which is what lets a test pin it: without it two equally good candidates would
 * come out in whatever order the provider felt like.
 *
 * No phonetic matching of its own (Soundex, Metaphone). That is an algorithm to write, tune and
 * prove, and it **fails differently**: it pairs things that are not there, which on a motorcycle is
 * calling the wrong person.
 */
object ContactMatcher {

    /**
     * A cursor over a long list with a helmet and gloves on is not a feature, it is a punishment. If
     * the right candidate is not in the first five, the name that was said did not serve and what
     * the rider has to do is speak again.
     *
     * **[rank] only.** The favourites are not capped, because speaking again is not an escape a
     * cut-off favourite has.
     */
    const val MAX_TARGETS = 5

    /**
     * Longest first: "llamar a " has to be tried before "llamar ", or the shorter one would eat the
     * verb and leave "a ana" behind.
     */
    private val COURTESY = listOf("llama a ", "llamar a ", "llama ", "llamar ", "call ", "phone ")
        .sortedByDescending { it.length }

    private val MARKS = Regex("\\p{Mn}+")
    private val NOT_LETTERS = Regex("[^\\p{L}\\p{Nd} ]+")
    private val SPACES = Regex(" +")

    /** What the platform lookup is given: normalised, and with the courtesy verb taken off. */
    fun queryFor(heard: String): String = stripCourtesy(normalize(heard))

    /**
     * Empty means [DialState.NoMatch] and nothing else: the empty list is the only condition.
     */
    fun rank(heard: String, found: List<Contact>): List<CallTarget> {
        val query = queryFor(heard)
        if (query.isEmpty()) return emptyList()
        val words = query.split(' ').filter { it.isNotEmpty() }

        return found
            .mapNotNull { contact ->
                qualityOf(words, query, normalize(contact.displayName))?.let { contact to it }
            }
            .sortedWith(
                compareBy(
                    { (_, quality) -> quality },
                    { (contact, _) -> if (contact.starred) 0 else 1 },
                    { (contact, _) -> normalize(contact.displayName) }
                )
            )
            .flatMap { (contact, _) -> targetsOf(contact) }
            .take(MAX_TARGETS)
    }

    /**
     * The starred contacts as a cursor. Alphabetical, because there is nothing said to rank them
     * against, and **uncapped**: what the rider starred is what he gets.
     *
     * [MAX_TARGETS] governs [rank] and nothing else. A search that came back wrong is escaped by
     * speaking again; a favourite cut off the end cannot be reached at all.
     */
    fun favorites(contacts: List<Contact>): List<CallTarget> = contacts
        .sortedBy { normalize(it.displayName) }
        .flatMap { targetsOf(it) }

    /**
     * The numbers of one contact, in the order the address book gave them — which is the order the
     * rider sees on his own phone — except that the default number goes first. `sortedBy` is stable,
     * so moving the primary one leaves the rest where they were.
     */
    private fun targetsOf(contact: Contact): List<CallTarget> = contact.numbers
        .sortedBy { if (it.isPrimary) 0 else 1 }
        .map { CallTarget(contact.displayName, it.value, it.kind) }

    /**
     * Best to worst, and `null` for no match at all: whole name identical, every word said is the
     * prefix of some word of the name, some word is, and the name contains what was heard anywhere.
     */
    private fun qualityOf(words: List<String>, query: String, name: String): Int? {
        val nameWords = name.split(' ').filter { it.isNotEmpty() }
        return when {
            name == query -> 0
            words.all { said -> nameWords.any { it.startsWith(said) } } -> 1
            words.any { said -> nameWords.any { it.startsWith(said) } } -> 2
            name.contains(query) -> 3
            else -> null
        }
    }

    /** Lower case, no diacritics, no punctuation, single spaces. `java.text`, never `android.*`. */
    private fun normalize(raw: String): String =
        Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace(NOT_LETTERS, " ")
            .replace(SPACES, " ")
            .trim()

    /**
     * A closed list and not a parse of the sentence: a list is read, tested and extended, while a
     * parse gets re-argued every time it fails. It only strips **when something is left behind**, so
     * the rider who answers "Ana" to "Say a name" is untouched and the one who says "llama a Ana"
     * does not fail either.
     */
    private fun stripCourtesy(normalized: String): String {
        val prefix = COURTESY.firstOrNull { normalized.startsWith(it) } ?: return normalized
        val rest = normalized.removePrefix(prefix).trim()
        return rest.ifEmpty { normalized }
    }
}
