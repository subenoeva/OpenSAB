package com.opensab.domain.contacts

/**
 * [search] is handed a query already normalised by [ContactMatcher.queryFor]: the platform half
 * feeds it to `CONTENT_FILTER_URI`, which is the indexed lookup Android's own dialler uses, and the
 * handful of rows it gives back are ordered here in pure Kotlin.
 */
interface ContactDirectory {

    /** Ordering is not promised. */
    suspend fun search(query: String): List<Contact>

    suspend fun starred(): List<Contact>
}
