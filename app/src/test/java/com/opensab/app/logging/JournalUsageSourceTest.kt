package com.opensab.app.logging

import com.opensab.logging.journal.ExportRequest
import com.opensab.logging.journal.JournalReader
import com.opensab.logging.journal.JournalUsage
import com.opensab.logging.journal.SessionListing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class JournalUsageSourceTest {

    @Test
    fun theFirstValueIsTheSnapshot() = runTest {
        val reader = FakeJournalReader(JournalUsage(10L, 100L))
        val source = JournalUsageSource(reader)

        assertEquals(JournalUsage(10L, 100L), source.usage.first())
    }

    @Test
    fun aListenerCallEmits() = runTest {
        val reader = FakeJournalReader(JournalUsage(0L, 100L))
        val source = JournalUsageSource(reader)
        val seen = mutableListOf<JournalUsage>()

        val job = launch { source.usage.toList(seen) }
        runCurrent()
        reader.listener?.invoke(JournalUsage(50L, 100L))
        runCurrent()
        job.cancel()

        assertEquals(
            listOf(JournalUsage(0L, 100L), JournalUsage(50L, 100L)),
            seen
        )
    }

    @Test
    fun cancellingTheCollectorClearsTheListener() = runTest {
        val reader = FakeJournalReader(JournalUsage(0L, 100L))
        val source = JournalUsageSource(reader)

        val job = launch { source.usage.collect {} }
        runCurrent()
        job.cancel()
        job.join()

        assertNull(reader.listener)
    }

    private class FakeJournalReader(override val usage: JournalUsage) : JournalReader {
        var listener: ((JournalUsage) -> Unit)? = null
            private set

        override fun setUsageListener(listener: ((JournalUsage) -> Unit)?) {
            this.listener = listener
        }

        override fun listSessions(callback: (List<SessionListing>) -> Unit) = callback(emptyList())

        override fun export(request: ExportRequest, callback: (Result<File>) -> Unit) =
            callback(Result.failure(UnsupportedOperationException()))
    }
}
