package com.opensab.domain.call

/**
 * The only place where "this is a call" gets decided, kept pure so the suite reaches it.
 *
 * Not a single localized string is read here. The notification's body text and its action labels
 * are translated, so matching them would work in one language and fail in the next; the status
 * comes from `android.callType`, which is an integer, and the filter from two platform constants.
 * Measured against Google Dialer on API 36.
 */
internal object CallNotificationParser {

    /** @return the call to show, or `null` when this notification is not one. */
    fun parse(notification: CallNotification): CallInfo? {
        // A missed call is turned down here too: it arrives with `category=missed_call` and no
        // CallStyle at all.
        if (notification.template != CALL_STYLE_TEMPLATE) return null
        if (notification.category != CATEGORY_CALL) return null

        val status = when (notification.callType) {
            CALL_TYPE_INCOMING -> CallStatus.RINGING
            CALL_TYPE_ONGOING -> CallStatus.ACTIVE
            // Call screening is 3, and the rest are not states this template defines. Neither is
            // something to put in front of a rider at 90 km/h.
            else -> return null
        }

        val person = notification.personName?.clean()
        val title = notification.title?.clean()

        return CallInfo(
            callerName = person?.ifBlank { null }
                ?: title?.ifBlank { null }
                ?: UNKNOWN_CALLER,
            callerNumber = notification.personUri?.clean()
                ?.takeIf { it.startsWith(TEL_SCHEME) }
                ?.removePrefix(TEL_SCHEME)
                ?: title?.takeIf { it.looksLikeANumber() }
                ?: "",
            status = status,
            // Never stamped here. The timestamp means "when this call showed up" and belongs to
            // the source, once per key: a parser that stamped it would return a different object
            // on every repost of a call that has not moved, and the engine would repaint on each.
            timestamp = 0L
        )
    }

    /**
     * Drops the Unicode format characters a dialer wraps its text in.
     *
     * Google Dialer publishes `android.title` as `"‪+34 600 11 12 22‬"` — bidi controls
     * that `dumpsys` prints invisibly. Left in, they defeat [looksLikeANumber] so every call would
     * carry an empty number, and they ride to the cluster, where `AsciiFoldingTextSanitizer` renders
     * each as `?` on indicator hardware.
     */
    private fun String.clean(): String =
        filterNot { it.category == CharCategory.FORMAT }.trim()

    private fun String.looksLikeANumber(): Boolean =
        isNotBlank() && all { it.isDigit() || it in NUMBER_PUNCTUATION }

    private const val CALL_STYLE_TEMPLATE = "android.app.Notification\$CallStyle"
    private const val CATEGORY_CALL = "call"
    private const val CALL_TYPE_INCOMING = 1
    private const val CALL_TYPE_ONGOING = 2
    private const val TEL_SCHEME = "tel:"
    private const val UNKNOWN_CALLER = "Unknown Caller"
    private const val NUMBER_PUNCTUATION = "+ ()-.#*"
}
