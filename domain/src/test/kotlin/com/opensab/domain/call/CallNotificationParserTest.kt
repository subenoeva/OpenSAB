package com.opensab.domain.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a posted notification has to be before the cluster calls it a call.
 *
 * Every vector here comes from a `dumpsys notification --noredact` taken against Google Dialer on
 * API 36, not from imagination. Returning null is a first-class answer: the
 * dialer posts the missed calls and their group summary in the very same package.
 */
class CallNotificationParserTest {

    private fun notification(
        key: String = DIALER_KEY,
        packageName: String = "com.google.android.dialer",
        category: String? = "call",
        template: String? = "android.app.Notification\$CallStyle",
        callType: Int? = 1,
        title: String? = "Laura",
        personName: String? = null,
        personUri: String? = null
    ) = CallNotification(
        key = key,
        packageName = packageName,
        category = category,
        template = template,
        callType = callType,
        title = title,
        personName = personName,
        personUri = personUri
    )

    @Test
    fun anIncomingCallStyleNotificationIsARingingCall() {
        val parsed = CallNotificationParser.parse(notification(callType = 1, title = "Laura"))

        assertNotNull(parsed)
        assertEquals(CallStatus.RINGING, parsed?.status)
        assertEquals("Laura", parsed?.callerName)
    }

    @Test
    fun anOngoingCallStyleNotificationIsAnActiveCall() {
        val parsed = CallNotificationParser.parse(notification(callType = 2, title = "Laura"))

        assertNotNull(parsed)
        assertEquals(CallStatus.ACTIVE, parsed?.status)
    }

    /**
     * Hanging up leaves `category=missed_call` on the `phone_missed_call` channel and **no**
     * `CallStyle`, so both rules turn it down on their own.
     */
    @Test
    fun aMissedCallNotificationIsNotACall() {
        assertNull(
            CallNotificationParser.parse(
                notification(
                    key = "0|com.google.android.dialer|1|MissedCall_content://call_log/calls/2|10137",
                    category = "missed_call",
                    template = null,
                    callType = null
                )
            )
        )
    }

    @Test
    fun aGroupSummaryOfMissedCallsIsNotACall() {
        assertNull(
            CallNotificationParser.parse(
                notification(
                    key = "0|com.google.android.dialer|1|GroupSummary_MissedCall|10137",
                    category = null,
                    template = null,
                    callType = null
                )
            )
        )
    }

    /** Rule 1 stands on its own: `category=call` without the platform template is not enough. */
    @Test
    fun aNotificationWithoutCallStyleIsNotACall() {
        assertNull(CallNotificationParser.parse(notification(template = "android.app.Notification\$BigTextStyle")))
    }

    /** And so does rule 2: the template without the category is not enough either. */
    @Test
    fun aNotificationWithCallStyleButAnotherCategoryIsNotACall() {
        assertNull(CallNotificationParser.parse(notification(category = "service")))
    }

    /**
     * Only 1 and 2 are states the rider should see. 3 is call screening — not something to put in
     * front of somebody at 90 km/h — and the rest are not states this template defines.
     */
    @Test
    fun anUnknownCallTypeIsNotACall() {
        listOf(0, 3, 4, null).forEach { callType ->
            assertNull(
                "callType=$callType must not produce a call",
                CallNotificationParser.parse(notification(callType = callType))
            )
        }
    }

    @Test
    fun thePersonNameWinsOverTheTitle() {
        val parsed = CallNotificationParser.parse(
            notification(title = "+34 600 11 12 22", personName = "Laura")
        )

        assertEquals("Laura", parsed?.callerName)
    }

    @Test
    fun theTitleIsUsedWhenThereIsNoPerson() {
        val parsed = CallNotificationParser.parse(notification(title = "Laura", personName = null))

        assertEquals("Laura", parsed?.callerName)
    }

    @Test
    fun aBlankIdentityFallsBackToUnknownCaller() {
        val parsed = CallNotificationParser.parse(notification(title = "   ", personName = null))

        assertEquals("Unknown Caller", parsed?.callerName)
    }

    @Test
    fun theNumberComesFromTheTelUriWhenThereIsOne() {
        val parsed = CallNotificationParser.parse(
            notification(title = "Laura", personName = "Laura", personUri = "tel:+34600111222")
        )

        assertEquals("+34600111222", parsed?.callerNumber)
        assertEquals("Laura", parsed?.callerName)
    }

    @Test
    fun theTitleIsUsedAsTheNumberWhenItLooksLikeOne() {
        val parsed = CallNotificationParser.parse(
            notification(title = "+34 600 11 12 22", personName = null, personUri = null)
        )

        assertEquals("+34 600 11 12 22", parsed?.callerNumber)
    }

    /**
     * Google Dialer wraps the number in bidi controls, and `dumpsys` prints them invisibly:
     * `android.title` is really `"‪+34 600 11 12 22‬"` — U+202A LEFT-TO-RIGHT EMBEDDING
     * and U+202C POP DIRECTIONAL FORMATTING, confirmed byte by byte with `od -c`.
     *
     * Two things break without stripping them. The "looks like a number" test fails, so
     * every Google Dialer call would carry an empty `callerNumber`; and the control characters ride
     * all the way to the cluster, where `AsciiFoldingTextSanitizer` turns each into a `?` on
     * indicator hardware and leaves them raw everywhere else.
     */
    @Test
    fun theBidiMarksTheDialerWrapsTheNumberInAreStripped() {
        val parsed = CallNotificationParser.parse(
            notification(title = "‪+34 600 11 12 22‬", personName = null, personUri = null)
        )

        assertEquals("+34 600 11 12 22", parsed?.callerName)
        assertEquals("+34 600 11 12 22", parsed?.callerNumber)
    }

    /**
     * The timestamp is the source's to stamp, once per key.
     *
     * `CallInfo.timestamp` defaults to `System.currentTimeMillis()`, so a parser that stamped it
     * would hand back a different object on every repost of a call that has not moved: the
     * StateFlow would emit, `CallInterruptSource` would re-emit and the engine would repaint. This
     * is that storm, killed at the root.
     */
    @Test
    fun theParserNeverStampsATimestamp() {
        val input = notification()

        val first = CallNotificationParser.parse(input)
        Thread.sleep(2)
        val second = CallNotificationParser.parse(input)

        assertEquals(first, second)
        assertEquals(0L, first?.timestamp)
    }

    /**
     * Not filtering by package is deliberate: the criterion is what the
     * phone presents as a call, and the rider neither knows nor should care who carries it.
     */
    @Test
    fun aCallFromAMessagingAppIsStillACall() {
        val parsed = CallNotificationParser.parse(
            notification(
                key = "0|com.whatsapp|1|null|10222",
                packageName = "com.whatsapp",
                title = "Elena"
            )
        )

        assertNotNull(parsed)
        assertEquals(CallStatus.RINGING, parsed?.status)
        assertEquals("Elena", parsed?.callerName)
    }

    private companion object {
        const val DIALER_KEY = "0|com.google.android.dialer|1|null|10137"
    }
}
