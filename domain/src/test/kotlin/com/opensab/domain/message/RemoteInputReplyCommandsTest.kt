package com.opensab.domain.message

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which handle the reply routes to, and what it does when there is none.
 *
 * Like `PendingIntentCallCommandsTest`, down to the reason the class is generic over its handle:
 * `NotificationCompat.Action` and the `PendingIntent` inside it cannot be built on the JVM, and
 * there is no mocking library on the test classpath, so the handle is a type parameter and the
 * suite instantiates it with something it can tell apart.
 *
 * Firing it for real is the one line no unit test reaches; only a run on the motorcycle closes it.
 */
class RemoteInputReplyCommandsTest {

    private val sent = mutableListOf<Pair<String, String>>()

    private fun commands(
        action: String? = REPLY_ACTION,
        send: suspend (String, String) -> Boolean = { handle, text ->
            sent += handle to text
            true
        }
    ) = RemoteInputReplyCommands(action = action, send = send)

    @Test
    fun `routes the reply to the action the notification came with`() = runTest {
        val answered = commands().reply("On my way")

        assertTrue("a reply through a live action must report success", answered)
        assertEquals(listOf(REPLY_ACTION to "On my way"), sent)
    }

    /**
     * The rider dictated it, so nothing here trims, capitalizes or appends to it. What reaches the
     * other phone is what was said.
     */
    @Test
    fun `hands the text over untouched`() = runTest {
        commands().reply("  see you at 8  ")

        assertEquals(listOf(REPLY_ACTION to "  see you at 8  "), sent)
    }

    /**
     * Not every messaging notification publishes a `RemoteInput`: a grouped summary and a muted
     * thread do not, and neither does an app outside the thirteen that do. That is a false
     * and never a throw, because the page turns it into `NotSent` and offers the retry.
     */
    @Test
    fun `reports failure when the notification carries no action`() = runTest {
        val answered = commands(action = null).reply("On my way")

        assertFalse("a reply with nothing to reply through must report failure", answered)
        assertEquals(emptyList<Pair<String, String>>(), sent)
    }

    /**
     * A `PendingIntent` the app has cancelled throws `CanceledException` when it is fired, and that
     * lands here. It is the same outcome as having no action at all — the rider is told the message
     * did not go — and it must not escape into the page's work job, which would kill the page
     * instead of painting `NotSent`.
     */
    @Test
    fun `reports failure when the send throws`() = runTest {
        val answered = commands(send = { _, _ -> error("the intent was cancelled") })
            .reply("On my way")

        assertFalse(answered)
    }

    /** A send that answers false is a send that failed, and it is reported as one. */
    @Test
    fun `reports failure when the send declines`() = runTest {
        assertFalse(commands(send = { _, _ -> false }).reply("On my way"))
    }

    /**
     * The one exception this must not turn into a false. Leaving the reply page cancels the job the
     * send is suspended in, and `CancellationException` is an `Exception` — swallowed here it would
     * report "not sent" for a message nobody was waiting on any more, and worse, leave the job it
     * belonged to running past its own cancellation (the same rule the recognizer wrapper obeys).
     */
    @Test
    fun `lets a cancellation through untouched`() = runTest {
        var thrown: Throwable? = null
        try {
            commands(send = { _, _ -> throw CancellationException("the page was left") })
                .reply("On my way")
        } catch (e: CancellationException) {
            thrown = e
        }

        assertEquals("the page was left", thrown?.message)
    }

    private companion object {
        const val REPLY_ACTION = "android.intent.extra.REMOTE_INPUT_reply"
    }
}
