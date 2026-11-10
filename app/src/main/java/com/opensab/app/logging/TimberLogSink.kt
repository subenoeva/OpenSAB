package com.opensab.app.logging

import com.opensab.logging.Log
import com.opensab.logging.LogSink
import timber.log.Timber

/**
 * Hands every [Log] call to Timber as if it had been a direct Timber call, so neither the logcat line
 * nor the session file line changes. Installed by `OpenSabApplication`.
 *
 * [verbose] gates level V and nothing else: the byte-by-byte frame dumps. D and above always pass,
 * because the session journal's summary and the field check count them.
 */
class TimberLogSink(private val verbose: () -> Boolean) : LogSink {
    override fun log(priority: Int, t: Throwable?, message: String?, args: Array<out Any?>) {
        if (priority == Log.VERBOSE && !verbose()) return
        Timber.log(priority, t, message, *args)
    }
}

/**
 * [Timber.DebugTree] tags a line with the class that called Timber. Behind the facade that class would
 * be [TimberLogSink] for every line, and the tag is part of the field contract:
 * field-log greps count `DefaultMessageSource: Message from `, because other apps log
 * "Message from" too.
 *
 * So when Timber hands this tree a facade frame, it looks further down the stack for the first frame
 * that belongs neither to Timber nor to the facade, and gives that one to Timber's own
 * [createStackElementTag]: anonymous-class suffixes and the length rule stay Timber's. A direct
 * Timber call, as the UI makes, never reaches the lookup. It never calls `Timber.tag()`, so
 * `FileLoggingTree` keeps receiving no tag.
 *
 * Open only so that `TimberLogSinkTest` can record lines instead of printing them.
 */
open class FacadeAwareDebugTree : Timber.DebugTree() {

    override fun createStackElementTag(element: StackTraceElement): String? {
        if (element.className !in FACADE) return super.createStackElementTag(element)
        val caller = Throwable().stackTrace.firstOrNull { it.className !in SKIPPED } ?: element
        return super.createStackElementTag(caller)
    }

    private companion object {
        val FACADE = setOf(Log::class.java.name, TimberLogSink::class.java.name)
        val SKIPPED = FACADE + setOf(
            Timber::class.java.name,
            Timber.Forest::class.java.name,
            Timber.Tree::class.java.name,
            Timber.DebugTree::class.java.name,
            FacadeAwareDebugTree::class.java.name,
        )
    }
}
