package com.opensab.sab.session

import com.opensab.sab.session.ble.MockGattTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionLinkHolderTest {

    private fun session(id: Long = 1L): SabSession = SabSession(
        id = id,
        macAddress = "AA:BB:CC:DD:EE:FF",
        origin = SessionOrigin.RECEIVER,
        parent = Dispatchers.Default,
        stallThreshold = null,
        onCloseRequested = { _, _ -> },
    )

    private fun transport(): MockGattTransport = MockGattTransport().apply { connect() }

    // --- current ---

    @Test
    fun currentIsNullByDefault() {
        val holder = LiveSessionLinkHolder()
        assertNull(holder.current)
    }

    // --- set ---

    @Test
    fun setMakesLinkVisibleViaCurrent() {
        val holder = LiveSessionLinkHolder()
        val link = LiveSessionLink(session = session(), transport = transport())
        holder.set(link)
        assertSame(link, holder.current)
    }

    @Test
    fun setOverwritesPreviousLink() {
        val holder = LiveSessionLinkHolder()
        val link1 = LiveSessionLink(session = session(1), transport = transport())
        val link2 = LiveSessionLink(session = session(2), transport = transport())
        holder.set(link1)
        holder.set(link2)
        assertSame(link2, holder.current)
    }

    // --- clear (compare-and-set) ---

    @Test
    fun clearReturnsTrueAndNullifiesWhenLinkMatches() {
        val holder = LiveSessionLinkHolder()
        val link = LiveSessionLink(session = session(), transport = transport())
        holder.set(link)
        assertTrue(holder.clear(link))
        assertNull(holder.current)
    }

    @Test
    fun clearReturnsFalseWhenLinkDoesNotMatch() {
        val holder = LiveSessionLinkHolder()
        val link1 = LiveSessionLink(session = session(1), transport = transport())
        val link2 = LiveSessionLink(session = session(2), transport = transport())
        holder.set(link1)
        assertFalse(holder.clear(link2))
        assertSame(link1, holder.current)
    }

    @Test
    fun clearReturnsFalseWhenAlreadyNull() {
        val holder = LiveSessionLinkHolder()
        val link = LiveSessionLink(session = session(), transport = transport())
        assertFalse(holder.clear(link))
        assertNull(holder.current)
    }

    // --- watchdogJob ---

    @Test
    fun watchdogJobDefaultsToNull() {
        val link = LiveSessionLink(session = session(), transport = transport())
        assertNull(link.watchdogJob.get())
    }

    @Test
    fun watchdogJobCanStoreAndRetrieveJob() {
        val link = LiveSessionLink(session = session(), transport = transport())
        val job = Job()
        link.watchdogJob.set(job)
        assertSame(job, link.watchdogJob.get())
    }

    @Test
    fun watchdogJobCompareAndSetReplacesAtomically() {
        val link = LiveSessionLink(session = session(), transport = transport())
        val job1 = Job()
        val job2 = Job()
        link.watchdogJob.set(job1)
        assertTrue(link.watchdogJob.compareAndSet(job1, job2))
        assertSame(job2, link.watchdogJob.get())
        assertFalse(link.watchdogJob.compareAndSet(job1, null))
        assertSame(job2, link.watchdogJob.get())
    }
}
