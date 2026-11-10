package com.opensab.app.source.music

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.os.BundleCompat
import com.opensab.app.di.InterceptGpsNotifications
import com.opensab.app.source.nav.MapsArrowCatalog
import com.opensab.domain.call.CallNotificationBus
import com.opensab.domain.message.HistoricMessage
import com.opensab.domain.message.MessageNotificationBus
import com.opensab.domain.nav.ArrowBits
import com.opensab.domain.nav.ArrowRecognition
import com.opensab.domain.nav.ArrowRecognizer
import com.opensab.domain.nav.NavigationNotificationBus
import com.opensab.domain.notifications.ListenedNotification
import com.opensab.domain.notifications.NotificationAction
import com.opensab.domain.notifications.NotificationDispatcher
import com.opensab.domain.notifications.NotificationPerson
import com.opensab.domain.notifications.NotificationRoute
import com.opensab.domain.notifications.PostedOutcome
import com.opensab.domain.notifications.ReplyInput
import com.opensab.domain.notifications.textInputsOf
import com.opensab.logging.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Translates [StatusBarNotification]s into [ListenedNotification]s and hands them to
 * [NotificationDispatcher], which decides where each one goes. This class translates and logs; the
 * lines stay here because the log tag is the class that wrote them, and field greps count on it.
 *
 * Android instantiates this service, so the buses arrive by field injection instead of constructor.
 */
@AndroidEntryPoint
class OpenSabNotificationListenerService : NotificationListenerService() {

    @Inject
    lateinit var callBus: CallNotificationBus

    @Inject
    lateinit var messageBus: MessageNotificationBus

    @Inject
    lateinit var navigationBus: NavigationNotificationBus

    @Inject
    lateinit var arrowCatalog: MapsArrowCatalog

    @Inject
    @InterceptGpsNotifications
    lateinit var interceptGps: StateFlow<Boolean>

    /** Owned here, one per service instance: it holds the unknown-arrow run between notifications. */
    private val dispatcher by lazy {
        NotificationDispatcher<PendingIntent, NotificationCompat.Action>(
            callBus = callBus,
            messageBus = messageBus,
            navigationBus = navigationBus,
            arrows = ArrowRecognizer(arrowCatalog::references),
            interceptNavigation = { interceptGps.value },
            sendIntent = { it.send() },
            sendReply = ::sendReply
        )
    }

    override fun onListenerConnected() {
        Log.i("Notification listener connected: media and navigation access available")
    }

    override fun onListenerDisconnected() {
        Log.w("Notification listener disconnected: media, navigation, message and call access lost")
        dispatcher.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val listened = sbn?.toListened() ?: return
        when (val outcome = dispatcher.onPosted(listened) { sbn.drawArrow() }) {
            is PostedOutcome.Call -> Log.d(
                "Call notification from %s: callType=%s, title=%s, key=%s",
                listened.packageName,
                outcome.notification.callType,
                outcome.notification.title,
                outcome.notification.key
            )

            is PostedOutcome.Navigation -> {
                logUnknownArrow(outcome.arrow)
                logNavigation(listened.packageName, outcome)
            }

            is PostedOutcome.UnreadableNavigation -> {
                logUnknownArrow(outcome.arrow)
                if (outcome.firstForKey) {
                    Log.w(
                        "Navigation notification unreadable, keeping the last maneuver: package=%s, key=%s",
                        listened.packageName,
                        listened.key
                    )
                }
            }

            PostedOutcome.Message, PostedOutcome.Ignored -> Unit
        }
    }

    private fun logUnknownArrow(arrow: ArrowRecognition) {
        if (arrow is ArrowRecognition.Unknown) {
            Log.w("Maps arrow unknown, nearest %s at %s bits:\n%s", arrow.nearest?.name, arrow.nearest?.distance, arrow.bits)
        }
    }

    private fun logNavigation(pkg: String, outcome: PostedOutcome.Navigation) {
        val arrow = outcome.arrow
        val instruction = outcome.instruction
        Log.d("Parsed navigation maneuver from $pkg: $instruction")
        if (arrow is ArrowRecognition.Known) {
            Log.d("Maps arrow %s at %d bits read as %s", arrow.match.name, arrow.match.distance, instruction.maneuver)
        } else {
            Log.d("Maps arrow none read as %s", instruction.maneuver)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val listened = sbn?.toListened() ?: return
        val pkg = listened.packageName
        val route = dispatcher.onRemoved(listened)
        if (route != NotificationRoute.IGNORE) {
            Log.d("Notification removed for %s: route=%s, key=%s", pkg, route, listened.key)
        }
        when (route) {
            NotificationRoute.NAVIGATION -> Log.d("Navigation notification removed for $pkg")
            NotificationRoute.MESSAGE -> Log.d("Message notification removed for $pkg")
            NotificationRoute.CALL, NotificationRoute.IGNORE -> Unit
        }
    }

    private fun StatusBarNotification.toListened(): ListenedNotification<PendingIntent, NotificationCompat.Action>? {
        val pkg = packageName ?: return null
        val notification = this.notification
        val extras = notification?.extras
        val flags = notification?.flags ?: 0
        val style = notification?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }

        return ListenedNotification(
            key = key,
            packageName = pkg,
            postTimeMillis = postTime,
            category = notification?.category,
            template = extras?.getString(EXTRA_TEMPLATE),
            isGroupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = flags and Notification.FLAG_ONGOING_EVENT != 0,
            isForegroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            texts = ListenedNotification.TEXT_EXTRAS
                .mapNotNull { name -> extras?.getCharSequence(name)?.let { name to it.toString() } }
                .toMap(),
            ints = ListenedNotification.INT_EXTRAS
                .mapNotNull { name -> extras?.takeIf { it.containsKey(name) }?.let { name to it.getInt(name) } }
                .toMap(),
            callPerson = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Api28Impl.extractPerson(extras) else null,
            answerIntent = extras?.pendingIntent(EXTRA_ANSWER_INTENT),
            declineIntent = extras?.pendingIntent(EXTRA_DECLINE_INTENT),
            hangUpIntent = extras?.pendingIntent(EXTRA_HANG_UP_INTENT),
            conversationTitle = style?.conversationTitle?.toString(),
            isGroupConversation = style?.isGroupConversation == true,
            messages = style?.messages.orEmpty().map { message ->
                HistoricMessage(
                    sender = message.person?.name?.toString(),
                    text = message.text?.toString().orEmpty(),
                    timestampMillis = message.timestamp
                )
            },
            actions = notification?.let(::actionsOf).orEmpty()
        )
    }

    private fun actionsOf(notification: Notification): List<NotificationAction<NotificationCompat.Action>> =
        (0 until NotificationCompat.getActionCount(notification))
            .mapNotNull { NotificationCompat.getAction(notification, it) }
            .map { action ->
                NotificationAction(
                    handle = action,
                    semanticAction = action.semanticAction,
                    acceptsFreeFormInput = action.remoteInputs?.any { it.allowFreeFormInput } == true,
                    hasIntent = action.actionIntent != null
                )
            }

    @RequiresApi(Build.VERSION_CODES.P)
    private object Api28Impl {
        @JvmStatic
        fun extractPerson(extras: Bundle?): NotificationPerson? {
            val person = extras?.let {
                BundleCompat.getParcelable(it, EXTRA_CALL_PERSON, Person::class.java)
            } ?: return null
            return NotificationPerson(name = person.name?.toString(), uri = person.uri)
        }
    }

    private fun Bundle.pendingIntent(key: String): PendingIntent? =
        BundleCompat.getParcelable(this, key, PendingIntent::class.java)

    private fun sendReply(packageName: String, target: NotificationCompat.Action, text: String): Boolean {
        val inputs = textInputsOf(
            target.remoteInputs.orEmpty().map {
                ReplyInput(
                    handle = it,
                    resultKey = it.resultKey,
                    allowFreeFormInput = it.allowFreeFormInput,
                    choices = it.choices.orEmpty().map(CharSequence::toString)
                )
            }
        ).toTypedArray()
        if (inputs.isEmpty()) {
            Log.w("Reply action of %s takes no free-form input, nothing sent", packageName)
            return false
        }
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        target.actionIntent?.send(this, 0, intent)
        return true
    }

    /** The large icon as [ArrowBits], or `null`; a drawing that throws is logged and read as no arrow. */
    private fun StatusBarNotification.drawArrow(): ArrowBits? = try {
        notification?.getLargeIcon()?.loadDrawable(this@OpenSabNotificationListenerService)?.let(arrowCatalog::arrowOf)
    } catch (e: Exception) {
        Log.w(e, "Maps arrow could not be drawn")
        null
    }

    private companion object {
        const val EXTRA_TEMPLATE = "android.template"
        const val EXTRA_CALL_PERSON = "android.callPerson"
        const val EXTRA_ANSWER_INTENT = "android.answerIntent"
        const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"
    }
}
