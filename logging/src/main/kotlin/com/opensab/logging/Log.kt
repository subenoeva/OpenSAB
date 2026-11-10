package com.opensab.logging

import java.util.concurrent.atomic.AtomicReference

/**
 * The one way code outside `ui/` logs. It has Timber's call shape and depends on nothing, so the
 * JVM modules can use it.
 *
 * Owned by `OpenSabApplication`, which installs the only sink once, in debuggable builds; a test
 * installs its own and uninstalls it in `finally`. With no sink every call is dropped, as Timber
 * does with no tree planted: a release build logs nothing, nor does a line written before
 * `Application.onCreate`.
 *
 * Any thread may log; each call reads the sink once, so the sink must be thread-safe. Nothing is
 * formatted or tagged here: `TimberLogSink` hands the arguments to Timber untouched.
 */
object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    private val sink = AtomicReference<LogSink?>(null)

    /** Installs the only sink. Fails if one is already installed: the application owns it. */
    fun install(sink: LogSink) {
        check(this.sink.compareAndSet(null, sink)) {
            "a log sink is already installed; OpenSabApplication owns it"
        }
    }

    /** Removes [sink] if it is the installed one. For tests: the application never uninstalls. */
    fun uninstall(sink: LogSink) {
        this.sink.compareAndSet(sink, null)
    }

    fun v(message: String?, vararg args: Any?) = emit(VERBOSE, null, message, args)
    fun v(t: Throwable?, message: String?, vararg args: Any?) = emit(VERBOSE, t, message, args)
    fun d(message: String?, vararg args: Any?) = emit(DEBUG, null, message, args)
    fun d(t: Throwable?, message: String?, vararg args: Any?) = emit(DEBUG, t, message, args)
    fun i(message: String?, vararg args: Any?) = emit(INFO, null, message, args)
    fun i(t: Throwable?, message: String?, vararg args: Any?) = emit(INFO, t, message, args)
    fun w(message: String?, vararg args: Any?) = emit(WARN, null, message, args)
    fun w(t: Throwable?, message: String?, vararg args: Any?) = emit(WARN, t, message, args)
    fun e(message: String?, vararg args: Any?) = emit(ERROR, null, message, args)
    fun e(t: Throwable?, message: String?, vararg args: Any?) = emit(ERROR, t, message, args)

    private fun emit(priority: Int, t: Throwable?, message: String?, args: Array<out Any?>) {
        sink.get()?.log(priority, t, message, args)
    }
}
