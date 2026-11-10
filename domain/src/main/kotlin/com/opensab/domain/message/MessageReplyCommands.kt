package com.opensab.domain.message

import kotlinx.coroutines.CancellationException
import com.opensab.logging.Log

/**
 * Replying to the notification the message arrived in.
 *
 * **Why this is not a field on [MessageInfo].** [MessageInfo] is a `data class` compared by
 * structural equality, and three live mechanisms depend on that equality — the "ask once per
 * message" of `MessagePromptPageProvider`, the "read once per message" of `MessagePageProvider`,
 * and the repost deduplication of [DefaultMessageSource]. A platform handle inside it would change
 * `equals` into something that differs on every repost of the same message, so the prompt would ask
 * again and the reading would read again. And replying **provokes** a repost.
 *
 * So the live action travels with the event and is owned by the source, the shape the call source
 * uses for `CallStyle`: [RemoteInputReplyCommands] mirrors
 * [com.opensab.domain.call.PendingIntentCallCommands], and [MessageInfo] stays as pure as `CallInfo`.
 */
interface MessageReplyCommands {

    /**
     * `false` is every way this can fail to happen — no action published, a `PendingIntent` the app
     * has cancelled, a send that declines — because the page does the same thing with all of them:
     * it paints `NotSent` and offers the retry. Nothing here throws, so a failed send cannot take
     * the page's work job down with it.
     */
    suspend fun reply(text: String): Boolean
}

/**
 * The handle is a type parameter and not `NotificationCompat.Action` for the reason
 * [com.opensab.domain.call.PendingIntentCallCommands] is generic over `PendingIntent`: neither can
 * be built on the JVM, there is no mocking library on the test classpath, and without the parameter
 * there would be no way to prove which handle a reply routes to. Production instantiates this with
 * the action; the suite does it with something it can tell apart.
 *
 * @param action the reply action of the live notification, or `null` when it published none — a
 *   grouped summary, a muted thread, or an app that simply does not offer one.
 * @param send the production value fills the `RemoteInput` bundle and sends the action's intent, and that
 *   call is the one line no unit test covers.
 */
class RemoteInputReplyCommands<T : Any>(
    private val action: T?,
    private val send: suspend (T, String) -> Boolean
) : MessageReplyCommands {

    override suspend fun reply(text: String): Boolean {
        val target = action
        if (target == null) {
            Log.w("No reply action on the message notification, nothing to reply through")
            return false
        }
        return try {
            Log.i("Replying through the notification's remote input")
            send(target, text)
        } catch (e: CancellationException) {
            // Leaving the reply page cancels the job this send is suspended in, and
            // `CancellationException` is an `Exception`: swallowed here it would report "not sent"
            // for a message nobody is waiting on any more and leave the job running past its own
            // cancellation. It is the one exception that is not a failed send.
            throw e
        } catch (e: Exception) {
            Log.w(e, "The notification's reply action could not be fired")
            false
        }
    }
}
