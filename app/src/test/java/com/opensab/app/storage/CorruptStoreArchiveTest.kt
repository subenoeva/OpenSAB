package com.opensab.app.storage

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The corruption handler is **silent and total**: one `CorruptionException` and
 * `ReplaceFileCorruptionHandler { emptyPreferences() }` drops the settings, every known motorcycle,
 * the favourite player and the weather cache at once, with nothing written down and nothing left
 * to look at.
 *
 * That is what this archives. The bytes are copied aside before DataStore overwrites them, so the
 * next session can say what was lost and why it would not parse, instead of guessing from a file
 * size.
 */
class CorruptStoreArchiveTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val stamp = 1_756_900_000_000L

    @Test
    fun keepsTheBytesOfTheStoreItIsAboutToLose() {
        val store = folder.newFile("vehicles.preferences_pb")
        val bytes = byteArrayOf(0x0A, 0x1F, 0x00, 0x7F)
        store.writeBytes(bytes)

        val archived = CorruptStoreArchive.preserve(store, stamp)

        assertTrue("nothing was archived", archived != null)
        assertArrayEquals(bytes, archived!!.readBytes())
    }

    /** Named after the store and the moment, so two corruptions do not overwrite each other. */
    @Test
    fun namesTheCopyAfterTheStoreAndTheMoment() {
        val store = folder.newFile("vehicles.preferences_pb")
        store.writeBytes(byteArrayOf(1))

        val archived = CorruptStoreArchive.preserve(store, stamp)

        assertEquals("vehicles.preferences_pb.corrupt-$stamp", archived?.name)
        assertEquals(store.parentFile, archived?.parentFile)
    }

    /** The original stays put: DataStore is the one that replaces it, and it does that next. */
    @Test
    fun leavesTheOriginalWhereItWas() {
        val store = folder.newFile("vehicles.preferences_pb")
        store.writeBytes(byteArrayOf(1, 2, 3))

        CorruptStoreArchive.preserve(store, stamp)

        assertTrue(store.exists())
        assertEquals(3, store.length())
    }

    /**
     * Archiving is best-effort and never the reason a launch fails: a store that is not there, or a
     * disk that will not take the copy, returns null and the app carries on with its defaults.
     */
    @Test
    fun aStoreThatIsNotThereArchivesNothing() {
        val missing = File(folder.root, "gone.preferences_pb")

        assertNull(CorruptStoreArchive.preserve(missing, stamp))
    }

    /**
     * And it destroys nothing on its way: an occupied destination costs the archive, not whatever
     * was already sitting there. A recovery path is the last place for a recursive delete.
     */
    @Test
    fun anOccupiedDestinationArchivesNothingInsteadOfThrowing() {
        val store = folder.newFile("vehicles.preferences_pb")
        store.writeBytes(byteArrayOf(1))
        val occupied = File(folder.root, "vehicles.preferences_pb.corrupt-$stamp")
        occupied.mkdir()
        File(occupied, "someone-elses.txt").writeText("keep me")

        assertNull(CorruptStoreArchive.preserve(store, stamp))

        assertTrue("the archive path was wiped to make room", occupied.isDirectory)
        assertEquals("keep me", File(occupied, "someone-elses.txt").readText())
    }
}
