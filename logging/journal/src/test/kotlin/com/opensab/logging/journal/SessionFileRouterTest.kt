package com.opensab.logging.journal

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFileRouterTest {

    private fun e(local: String, msg: String, p: Char = 'I') =
        JournalEntryParser.parse(sequenceOf("$local [$p/OpenSAB] $msg"), MADRID).single()

    private fun replay(router: SessionFileRouter, entries: List<JournalEntry>) =
        entries.flatMap { router.route(it) }

    @Test
    fun theDailyUseGoldenOpensAndClosesSixSessions() {
        val actions = replay(SessionFileRouter(), JournalEntryParser.parse(golden("daily-use.log"), MADRID))
        assertEquals(
            listOf(
                "2026-09-23_19-41-33_s1", "2026-09-23_20-56-18_s2", "2026-09-23_21-17-22_s3",
                "2026-09-24_20-24-13_s1", "2026-09-24_21-31-47_s2", "2026-09-24_21-32-20_s3"
            ),
            actions.filterIsInstance<RouteAction.Open>().map { it.session.baseName }
        )
        assertEquals(
            List(6) { CloseKind.CLOSED },
            actions.filterIsInstance<RouteAction.Close>().map { it.kind }
        )
    }

    @Test
    fun entriesOutsideASessionGoToTheirDay() {
        val actions = replay(SessionFileRouter(), JournalEntryParser.parse(golden("daily-use.log"), MADRID))
        assertEquals(LocalDate.of(2026, 9, 23), (actions.first() as RouteAction.ToDay).day)
        assertTrue(actions.filterIsInstance<RouteAction.ToDay>().any { it.day == LocalDate.of(2026, 9, 24) })
    }

    @Test
    fun theOpeningCopiesTheLastThirtySecondsAsItsPrologue() {
        val router = SessionFileRouter()
        router.route(e("2026-09-23 20:56:18.000", "old", 'D'))            // 30.790 s before: out
        router.route(e("2026-09-23 20:56:48.776",
            "Failed to start foreground service with type 152, falling back to CONNECTED_DEVICE", 'W'))
        val actions = router.route(e("2026-09-23 20:56:48.790",
            "SabSession #2 opened for 02:00:00:00:00:01 (origin=RECEIVER)"))
        val open = actions.filterIsInstance<RouteAction.Open>().single()
        assertEquals(listOf("Failed to start foreground service with type 152, falling back to CONNECTED_DEVICE"),
            open.prologue.map { it.firstLine })
        assertTrue(actions.last() is RouteAction.ToSession)
    }

    @Test
    fun theImmediateReopenKeepsEachEntryInOneOwner() {
        val entries = JournalEntryParser.parse(golden("daily-use.log"), MADRID)
        val router = SessionFileRouter()
        val actions = replay(router, entries)
        val fgs = entries.single {
            it.local == LocalDateTime.parse("2026-09-24T21:32:20.499") && it.firstLine.startsWith("Failed to start foreground")
        }
        val owners = actions.filterIsInstance<RouteAction.ToSession>().filter { it.entry == fgs }
        assertEquals(listOf("2026-09-24_21-31-47_s2"), owners.map { it.session.baseName })
        val reopen = actions.filterIsInstance<RouteAction.Open>().single { it.session.baseName == "2026-09-24_21-32-20_s3" }
        assertTrue(fgs in reopen.prologue)
    }

    @Test
    fun aClosingWithoutClosedInClosesTenSecondsLater() {
        val entries = listOf(e("2026-09-14 14:04:30.000", "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)")) +
            JournalEntryParser.parse(golden("missing-closed.log"), MADRID)
        val actions = replay(SessionFileRouter(), entries)
        assertEquals(listOf(CloseKind.MISSING_CLOSED), actions.filterIsInstance<RouteAction.Close>().map { it.kind })
        // Entries within the 10 s after closing still belong to the session.
        assertTrue(actions.filterIsInstance<RouteAction.ToSession>().any { it.entry.firstLine.startsWith("FunctionNotification observation failed") })
        // The multi-line notification key of 15:43:51.723 is a day entry, whole.
        val keyEntry = actions.filterIsInstance<RouteAction.ToDay>().first { it.entry.firstLine.startsWith("Notification removed") }
        assertTrue(keyEntry.entry.message.endsWith("\n|10271"))
    }

    @Test
    fun aTickClosesASilentSession() {
        val router = SessionFileRouter()
        router.route(e("2026-09-24 21:00:00.000", "SabSession #4 opened for 02:00:00:00:00:01 (origin=RECEIVER)"))
        router.route(e("2026-09-24 21:05:00.000", "SabSession #4 closing (LINK_LOST), 17 close actions"))
        assertEquals(emptyList<RouteAction>(), router.tick(Instant.parse("2026-09-24T19:05:09.999Z")))
        val closed = router.tick(Instant.parse("2026-09-24T19:05:10.000Z"))
        assertEquals(CloseKind.MISSING_CLOSED, (closed.single() as RouteAction.Close).kind)
        assertNull(router.open)
    }

    @Test
    fun aNewOpeningClosesTheOneLeftOpen() {
        val router = SessionFileRouter()
        router.route(e("2026-09-24 21:00:00.000", "SabSession #1 opened for 02:00:00:00:00:01 (origin=RECEIVER)"))
        val actions = router.route(e("2026-09-24 21:01:00.000", "SabSession #2 opened for 02:00:00:00:00:01 (origin=RECEIVER)"))
        assertEquals(CloseKind.MISSING_CLOSED, (actions.first() as RouteAction.Close).kind)
        assertEquals(2, router.open!!.number)
    }

    @Test
    fun aSessionStillClosingKeepsItsClosedInWhenTheNextOneOpens() {
        // Field, 2026-09-25 21:28:17: the ignition came back while #2 was closing.
        val router = SessionFileRouter()
        router.route(e("2026-09-25 21:20:27.334", "SabSession #2 opened for 02:00:00:00:00:01 (origin=RECEIVER)"))
        router.route(e("2026-09-25 21:28:17.093", "SabSession #2 closing (LINK_LOST), 17 close actions"))
        val reopen = router.route(e("2026-09-25 21:28:17.221", "SabSession #3 opened for 02:00:00:00:00:01 (origin=RECEIVER)"))
        assertEquals(emptyList<RouteAction.Close>(), reopen.filterIsInstance<RouteAction.Close>())

        val closedIn = router.route(e("2026-09-25 21:28:17.227", "SabSession #2 closed in 134 ms"))

        assertEquals(listOf(2), closedIn.filterIsInstance<RouteAction.ToSession>().map { it.session.number })
        assertEquals(
            listOf(2 to CloseKind.CLOSED),
            closedIn.filterIsInstance<RouteAction.Close>().map { it.session.number to it.kind }
        )
        assertEquals(3, router.open!!.number)
    }

    @Test
    fun aSessionStillClosingWhenTheNextOneOpensIsMissingClosedTenSecondsLater() {
        val router = SessionFileRouter()
        router.route(e("2026-09-25 21:20:27.334", "SabSession #2 opened for 02:00:00:00:00:01 (origin=RECEIVER)"))
        router.route(e("2026-09-25 21:28:17.093", "SabSession #2 closing (LINK_LOST), 17 close actions"))
        router.route(e("2026-09-25 21:28:17.221", "SabSession #3 opened for 02:00:00:00:00:01 (origin=RECEIVER)"))

        val closed = router.tick(Instant.parse("2026-09-25T19:28:27.093Z"))

        assertEquals(
            listOf(2 to CloseKind.MISSING_CLOSED),
            closed.filterIsInstance<RouteAction.Close>().map { it.session.number to it.kind }
        )
        assertEquals(3, router.open!!.number)
    }

    @Test
    fun aClosedInWithAnotherNumberDoesNotClose() {
        val router = SessionFileRouter()
        router.route(e("2026-09-24 21:00:00.000", "SabSession #3 opened for 02:00:00:00:00:01 (origin=RECEIVER)"))
        val actions = router.route(e("2026-09-24 21:00:01.000", "SabSession #2 closed in 69 ms"))
        assertTrue(actions.single() is RouteAction.ToSession)
        assertEquals(3, router.open!!.number)
    }

    @Test
    fun theBaseNameIsLocalTimeAndSessionNumber() =
        assertEquals("2026-09-24_20-24-13_s1", SessionFileNames.baseName(LocalDateTime.parse("2026-09-24T20:24:13.772"), 1))
}
