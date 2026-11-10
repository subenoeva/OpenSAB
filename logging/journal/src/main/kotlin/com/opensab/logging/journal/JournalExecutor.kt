package com.opensab.logging.journal

import java.time.Duration
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owner: the SessionJournal that received it; nothing else submits work to it. */
interface JournalExecutor {
    /** `false` if the task was refused (the executor is shut down) and will never run. */
    fun execute(task: () -> Unit): Boolean
    fun schedule(delay: Duration, task: () -> Unit)

    /** Runs what is already queued (not what is scheduled for later) and waits up to 5 s. */
    fun shutdown()

    companion object {
        /** One daemon thread named "session-journal". */
        fun singleThread(): JournalExecutor = SingleThreadJournalExecutor()
    }
}

private class SingleThreadJournalExecutor : JournalExecutor {
    private val pool = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "session-journal").apply { isDaemon = true }
    }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    // A log call can still arrive after shutdown; the work it would queue is simply not done.
    override fun execute(task: () -> Unit): Boolean = try {
        pool.execute { task() }
        true
    } catch (e: RejectedExecutionException) {
        false
    }

    override fun schedule(delay: Duration, task: () -> Unit) {
        try {
            pool.schedule({ task() }, delay.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // Same as execute(): after shutdown the scheduled work is simply not done.
        }
    }

    override fun shutdown() {
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }
}
