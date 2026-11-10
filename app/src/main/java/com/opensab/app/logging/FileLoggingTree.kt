package com.opensab.app.logging

import android.util.Log
import com.opensab.logging.journal.JournalSink
import timber.log.Timber

/**
 * Hands each Timber line to the session journal, which owns every file. Timber has already appended
 * the throwable's stack trace to message, so t is not written again.
 */
class FileLoggingTree(private val sink: JournalSink) : Timber.Tree() {

    public override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        sink.append(letterOf(priority), tag ?: "OpenSAB", message)
    }

    private fun letterOf(priority: Int) = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> '?'
    }
}
