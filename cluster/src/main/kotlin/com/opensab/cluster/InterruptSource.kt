package com.opensab.cluster

import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallStatus
import com.opensab.domain.settings.InboundCallMode
import com.opensab.domain.settings.InboundMessageMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart

/**
 * A page that takes the screen without the rider asking for it.
 *
 * The engine mounts what this emits on the interrupt stack of [NavigationStack], so the page
 * receives handlebar presses through the one path every page uses. That is the whole point: an
 * external event never gets an input route of its own. An adapter that listened to
 * `InputDispatcher.events` itself would be a second owner of the press, and two owners is how the
 * mirror ends up labelling one thing while the engine does another.
 */
interface InterruptSource {

    /**
     * The page that must hold the interrupt stack right now, or `null` to withdraw it.
     *
     * Emitting the **same instance** again means the page's own state moved and the frame has to be
     * redrawn — a call going from `RINGING` to `ACTIVE` is the case that matters — so the engine
     * invalidates instead of mounting a second copy.
     */
    val interrupting: Flow<PageProvider?>
}

/** Nothing ever claims the screen. The default for an engine wired without a telephony source. */
object NoInterruptions : InterruptSource {
    override val interrupting: Flow<PageProvider?> = emptyFlow()
}

/**
 * Turns telephony state into a claim on the screen according to [InboundCallMode].
 *
 * One [PageProvider] instance serves the whole call: it reads the same live `activeCall` flow this
 * class watches, so `RINGING` to `ACTIVE` is a re-render of the mounted page and not a new mount.
 * That is what `ResumeBehavior.RETAIN` on the call page already assumed.
 *
 * A call claims the screen while it is [CallStatus.RINGING] or [CallStatus.ACTIVE] — the very
 * predicate that already governs the PHONE indicator. Keeping the two identical is what stops the
 * bit and the page from disagreeing.
 *
 * @param mode in [InboundCallMode.IGNORE] the call does not take the screen, but still lights the
 *   PHONE bit and still opens from the carousel.
 */
class CallInterruptSource(
    activeCall: StateFlow<CallInfo?>,
    mode: Flow<InboundCallMode>,
    callPageFactory: () -> PageProvider
) : InterruptSource {

    private val page: PageProvider by lazy(callPageFactory)

    override val interrupting: Flow<PageProvider?> =
        combine(
            activeCall,
            mode.onStart { emit(DEFAULT_MODE) }.distinctUntilChanged()
        ) { call, currentMode ->
            if (call.isLive && currentMode == InboundCallMode.ALERT) page else null
        }

    private companion object {
        val DEFAULT_MODE = InboundCallMode.ALERT

        val CallInfo?.isLive: Boolean
            get() = this != null && (status == CallStatus.RINGING || status == CallStatus.ACTIVE)
    }
}

/**
 * Turns an unread message into a claim on the screen according to [InboundMessageMode].
 *
 * One [PageProvider] instance serves each role (prompt vs read) for the whole session.
 *
 * The claim is [MessageSource.claimingScreen] and not `currentMessage != null`, and the difference
 * is the whole of `@Back`: yielding the screen leaves the message unread — carousel entry open, bit
 * lit — while withdrawing the interruption.
 */
class MessageInterruptSource(
    claimingScreen: StateFlow<Boolean>,
    mode: Flow<InboundMessageMode>,
    promptPageFactory: () -> PageProvider,
    readPageFactory: () -> PageProvider
) : InterruptSource {

    private val promptPage: PageProvider by lazy(promptPageFactory)
    private val readPage: PageProvider by lazy(readPageFactory)

    override val interrupting: Flow<PageProvider?> =
        combine(
            claimingScreen,
            mode.onStart { emit(DEFAULT_MODE) }.distinctUntilChanged()
        ) { claiming, currentMode ->
            if (!claiming) {
                null
            } else {
                when (currentMode) {
                    InboundMessageMode.PROMPT -> promptPage
                    InboundMessageMode.READ_DIRECTLY -> readPage
                    InboundMessageMode.IGNORE -> null
                }
            }
        }

    private companion object {
        val DEFAULT_MODE = InboundMessageMode.PROMPT
    }
}

/**
 * Several claims on one screen, resolved by the order they are given in.
 *
 * [BaseStateManager] mounts one interrupt at a time, so with telephony and messaging both able to
 * claim it somebody has to say who outranks whom: a ringing call beats an arriving message. When
 * the call ends the message is still unread, so its claim surfaces on its own — that is `combine`
 * doing the arbitration, not a special case anybody had to write.
 *
 * Two details are load-bearing and neither is decoration:
 *
 * - Every source is prefixed with `onStart { emit(null) }`. [NoInterruptions] is an `emptyFlow()`,
 *   and `combine` with a flow that never emits never emits either, so without the prefix one silent
 *   source would mute the whole composite for the session.
 * - There is deliberately **no** `distinctUntilChanged`. Re-emitting the same instance is how a
 *   mounted page says "redraw me" ([InterruptSource]), and swallowing it would freeze a call on
 *   RINGING.
 */
internal class PriorityInterruptSource(private vararg val sources: InterruptSource) : InterruptSource {

    override val interrupting: Flow<PageProvider?> =
        combine(sources.map { it.interrupting.onStart { emit(null) } }) { claims ->
            claims.firstNotNullOfOrNull { it }
        }
}
