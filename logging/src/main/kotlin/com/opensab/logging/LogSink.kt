package com.opensab.logging

/**
 * Where [Log] delivers. Exactly one is installed, and the application installs it (see [Log]).
 * [priority] is one of [Log.VERBOSE], [Log.DEBUG], [Log.INFO], [Log.WARN] or [Log.ERROR]; [message] and [args]
 * arrive unformatted. Called from any thread.
 */
fun interface LogSink {
    fun log(priority: Int, t: Throwable?, message: String?, args: Array<out Any?>)
}
