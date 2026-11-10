package com.opensab.domain.call

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Why the motorcycle is not seeing calls, answered without `adb`.
 *
 * Pure, so the rule lives where the suite reaches it and the Compose row only paints what it is
 * told.
 */
class CallAccessPolicyTest {

    @Test
    fun readyWhenTheListenerIsEnabled() {
        assertEquals(
            CallAccessState.READY,
            CallAccessPolicy.resolve(
                sdkInt = 31,
                listenerEnabled = true
            )
        )
    }

    /**
     * The one the rider can act on, and the pleasant part of this route: it is the very screen the
     * app already sends them to for music, so a rider who plays music has nothing to grant.
     */
    @Test
    fun needsSystemScreenWhenTheListenerIsOff() {
        assertEquals(
            CallAccessState.NEEDS_NOTIFICATION_ACCESS,
            CallAccessPolicy.resolve(
                sdkInt = 36,
                listenerEnabled = false
            )
        )
    }

    /**
     * `Notification$CallStyle` is API 31 and `minSdk` is 26, so between 26 and 30 this feature does
     * not exist. Saying so is the whole point: without it the parser turns every notification down
     * by template, nothing errors, and the rider never learns why the cluster never rings.
     */
    @Test
    fun unsupportedBelowApi31() {
        listOf(26, 29, 30).forEach { sdkInt ->
            assertEquals(
                "API $sdkInt has no CallStyle, so there is no route at all",
                CallAccessState.UNSUPPORTED,
                CallAccessPolicy.resolve(
                    sdkInt = sdkInt,
                    listenerEnabled = true
                )
            )
        }
    }

    /** No gap and no overlap: every combination lands on exactly one state. */
    @Test
    fun everyCombinationOfTheTableResolvesToExactlyOneState() {
        val resolved = listOf(26, 30, 31, 36).flatMap { sdkInt ->
            listOf(true, false).map { listener ->
                (sdkInt to listener) to CallAccessPolicy.resolve(sdkInt, listener)
            }
        }

        assertEquals(8, resolved.size)
        assertEquals(
            "the version gate comes first, then the listener, and nothing else decides",
            resolved.associate { (input, _) -> input to expected(input) },
            resolved.associate { (input, state) -> input to state }
        )
    }

    private fun expected(input: Pair<Int, Boolean>): CallAccessState {
        val (sdkInt, listenerEnabled) = input
        return when {
            sdkInt < 31 -> CallAccessState.UNSUPPORTED
            !listenerEnabled -> CallAccessState.NEEDS_NOTIFICATION_ACCESS
            else -> CallAccessState.READY
        }
    }
}
