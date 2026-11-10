package com.opensab.logging.journal

import java.io.File
import java.time.Instant

/** A retained unit on disk: a session (its `.log` and, once written, its `.json`) or a day's `.log`. */
data class StoredUnit(val id: String, val sortKey: Instant, val bytes: Long, val files: List<File>)

/** Picks what to delete, oldest [StoredUnit.sortKey] first, so the total fits under a byte limit. */
object JournalRetention {

    /** Oldest first until under [limitBytes]; never a unit whose id is in [protectedIds]. */
    fun plan(units: List<StoredUnit>, limitBytes: Long, protectedIds: Set<String>): List<StoredUnit> {
        var over = units.sumOf { it.bytes } - limitBytes
        if (over <= 0) return emptyList()
        val toDelete = mutableListOf<StoredUnit>()
        for (unit in units.filterNot { it.id in protectedIds }.sortedBy { it.sortKey }) {
            if (over <= 0) break
            toDelete += unit
            over -= unit.bytes
        }
        return toDelete
    }
}
