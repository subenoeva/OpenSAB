package com.opensab.logging.journal

import java.time.Duration

/** Runs nothing until the test says so: [runAll] for what is queued, [advanceBy] for what is scheduled. */
class ManualJournalExecutor : JournalExecutor {
    private val queued = ArrayDeque<() -> Unit>()
    private val scheduled = mutableListOf<Pair<Duration, () -> Unit>>()
    private var now: Duration = Duration.ZERO

    override fun execute(task: () -> Unit): Boolean {
        queued.addLast(task)
        return true
    }

    override fun schedule(delay: Duration, task: () -> Unit) {
        scheduled += now.plus(delay) to task
    }

    override fun shutdown() = runAll()

    fun runAll() {
        while (queued.isNotEmpty()) queued.removeFirst().invoke()
    }

    /** Advances the virtual clock, running each scheduled task that falls due in order, then the queue. */
    fun advanceBy(duration: Duration) {
        val target = now.plus(duration)
        while (true) {
            val next = scheduled.filter { it.first <= target }.minByOrNull { it.first } ?: break
            scheduled.remove(next)
            now = next.first
            next.second.invoke()
            runAll()
        }
        now = target
        runAll()
    }
}
