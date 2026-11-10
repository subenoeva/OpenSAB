package com.opensab.domain.call

import com.opensab.logging.Log

/**
 * The three call controls, routed to the three handles the notification came with.
 *
 * `CallStyle` publishes `android.answerIntent` and `android.declineIntent` while the call rings and
 * `android.hangUpIntent` once it is up, all three as `PendingIntent`s of the dialer's own — so
 * answering from the handlebar is firing the very button the notification shade shows, with no
 * deprecated API and no translated action label anywhere on the path.
 *
 * `SELECT` answers, `@Back` rejects while ringing and hangs up once the call is up.
 *
 * The handle is a type parameter rather than `PendingIntent` outright, and that is not decoration.
 * `PendingIntent` is final, has no public constructor and its stubs throw, so with no mocking
 * library on the test classpath there would be no way to prove which handle each command routes
 * to. Production instantiates this with `PendingIntent`; the suite does it with something it can
 * tell apart.
 *
 * @param fallback used when the handle for a command is missing. It is wired to `null` today: no
 *   dialer has been observed publishing `CallStyle` without its intents, and the telecom permission
 *   a standby would need is declared but requested by no screen, so it would be dead code holding a
 *   dangerous permission open.
 * @param send the production value is `{ it.send() }`, and that call is the one line no unit test
 *   covers.
 */
class PendingIntentCallCommands<T : Any>(
    private val answerIntent: T?,
    private val declineIntent: T?,
    private val hangUpIntent: T?,
    private val fallback: CallCommands? = null,
    private val send: (T) -> Unit
) : CallCommands {

    override fun answer() = fire(answerIntent, "answer") { fallback?.answer() }

    override fun reject() = fire(declineIntent, "reject") { fallback?.reject() }

    override fun disconnect() = fire(hangUpIntent, "disconnect") { fallback?.disconnect() }

    private inline fun fire(intent: T?, name: String, onMissing: () -> Unit) {
        if (intent == null) {
            Log.w("No %s intent on the call notification, falling back", name)
            onMissing()
            return
        }
        Log.i("Firing the notification's %s intent", name)
        send(intent)
    }
}
