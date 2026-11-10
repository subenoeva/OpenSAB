package com.opensab.logging.journal

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalRetentionTest {

    private fun unit(id: String, sortKey: String, bytes: Long) =
        StoredUnit(id, Instant.parse(sortKey), bytes, emptyList())

    @Test
    fun retentionDeletesByInstantNotByName() {
        // After a DST fall-back, the later session has the earlier-looking name.
        val older = unit("2026-10-25_02-30-00_s1-2", "2026-10-25T00:30:00Z", 30)
        val newer = unit("2026-10-25_02-10-00_s1", "2026-10-25T01:10:00Z", 30)
        assertEquals(listOf(older), JournalRetention.plan(listOf(newer, older), limitBytes = 40, protectedIds = emptySet()))
    }

    @Test
    fun theOpenSessionAndTodaysDayAreNeverDeleted() {
        val open = unit("open", "2026-09-01T00:00:00Z", 100)
        val today = unit("days/2026-09-24", "2026-09-24T00:00:00Z", 100)
        assertEquals(emptyList<StoredUnit>(), JournalRetention.plan(listOf(open, today), 10, setOf("open", "days/2026-09-24")))
    }

    @Test
    fun daysAndSessionsShareOneOldestFirstOrder() {
        val day = unit("days/2026-09-20", "2026-09-19T22:00:00Z", 10)
        val session = unit("2026-09-21_10-00-00_s1", "2026-09-21T08:00:00Z", 10)
        assertEquals(listOf(day), JournalRetention.plan(listOf(session, day), 10, emptySet()))
    }
}
