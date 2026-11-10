package com.opensab.cluster.message

import com.opensab.cluster.BaseStateManager
import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.cluster.redrawOn
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.ClusterToken
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.message.MessageSource
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.speech.voiceLocaleFor
import com.opensab.logging.Log
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * **It reads the body out loud when it mounts**, in the voice of the alphabet the message arrived
 * in. It names the phrase; the engine says it, because only the engine knows the instant this page
 * stops being mounted (see [SpeakingPage]).
 *
 * `BACK` and `MENU` yield the screen with the message still unread; `SELECT` replies, when the
 * notification can be replied to.
 */
class MessagePageProvider(
    private val currentMessage: StateFlow<MessageInfo?>,
    private val copy: ClusterTextResolver,
    private val messageSource: MessageSource? = null,
    /**
     * Whether the live notification published a way to answer it.
     *
     * A property of the notification and not of the app, and not of [MessageInfo] either — see
     * [com.opensab.domain.message.MessageReplyCommands] for why that class stays a POJO. It governs
     * the right button of the frame, the `SELECT` branch and the `PRIMARY` zone of the mirror off
     * this one flow, so a thread that cannot be answered closes all three together.
     */
    private val canReply: StateFlow<Boolean> = MutableStateFlow(false),
    private val volumeSource: VolumeSource? = null,
    /**
     * The dictation page, resolved on the first press and kept.
     *
     * One instance per session for the reason the reading itself is one: a fresh page on every
     * press would give one dictation a new identity each time, and the engine tells a remount from
     * a redraw by identity.
     */
    replyPageFactory: () -> PageProvider = { error("no reply page wired") }
) : SpeakingPage {

    private val replyPage: PageProvider by lazy(replyPageFactory)

    override val name: String = "MessagePage"

    /**
     * The message this page has already read out.
     *
     * Owned here and nowhere else, and safe as a plain `var` because [BaseStateManager] holds the
     * only collector of [speech] and cancels it before starting another (see [SpeakingPage]).
     *
     * Reading is once **per message**, not once per mount: a call covering the page and going away
     * is a remount, and it must not start the body again from the top. The next message of the
     * thread is a different [MessageInfo] and does get read.
     */
    private var readOut: MessageInfo? = null

    /**
     * The body, once, in the voice of its own alphabet.
     *
     * An empty body says nothing at all: an utterance with no words is worse than silence, and the
     * frame already falls back to the message icon in that case.
     *
     * There is no key to hear it again, and that is deliberate. The weather page repeats on
     * `SELECT` because it has nothing else to do with it; here `SELECT` is reserved for replying.
     */
    override val speech: Flow<List<SpeechRequest>> = currentMessage
        .filterNotNull()
        .filter { it != readOut && it.body.isNotEmpty() }
        .onEach { readOut = it }
        .map { message ->
            listOf(
                SpeechRequest(
                    token = SpeechToken.MESSAGE_READ,
                    args = listOf(SpeechArg.Text(message.body)),
                    locale = voiceLocaleFor(message.body)
                )
            )
        }

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.DISCARD

    override fun render(requestId: Int): ProjectionFrame {
        val header = copy.resolve(ClusterToken.HEADER_MESSAGES)
        val message = currentMessage.value ?: return LiveScreenPresets.messageStandby(
            requestId = requestId,
            header = header,
            empty = copy.resolve(ClusterToken.EMPTY_MESSAGES)
        )
        val sender = message.sender.ifEmpty { header }
        return LiveScreenPresets.message(
            requestId = requestId,
            header = header,
            sender = sender,
            body = message.body,
            canReply = canReply.value
        )
    }

    /**
     * The shared content-page block, with the two differences this page earns in [onInput]:
     *
     * - `BACK` carries [MirrorActionToken.DISMISS_MESSAGE] and not `GO_BACK`, because the branch
     *   calls `yieldScreen()` before popping: a zone labelled "back" would understate what the
     *   press does to the message on screen.
     * - **`SELECT` carries [MirrorActionToken.REPLY_MESSAGE], and it is declared even when there is
     *   nothing to reply to** — disabled, never absent. A zone that vanishes shifts the rest of the
     *   card under a glance, and the mirror does not get to hide a function the rider has on the
     *   handlebar; the shape `READ_MESSAGE` already takes on the prompt page in standby.
     *
     * The list is the same on standby: `BACK` still clears and pops, and the volume source is still
     * the page's own.
     */
    override fun mirror(): MirrorPage = mirrorPageOf(
        name,
        render(MIRROR_REQUEST_ID),
        actions = listOf(
            MirrorAction(
                token = MirrorActionToken.DISMISS_MESSAGE,
                functionId = FunctionId.BACK,
                slot = MirrorSlot.LEFT
            ),
            MirrorAction(
                token = MirrorActionToken.REPLY_MESSAGE,
                functionId = FunctionId.SELECT,
                slot = MirrorSlot.PRIMARY,
                enabled = canReplyNow()
            ),
            MirrorAction(
                token = MirrorActionToken.VOLUME_UP,
                functionId = FunctionId.PREVIOUS,
                slot = MirrorSlot.UP,
                aliases = setOf(FunctionId.VOLUME_UP)
            ),
            MirrorAction(
                token = MirrorActionToken.VOLUME_DOWN,
                functionId = FunctionId.NEXT,
                slot = MirrorSlot.DOWN,
                aliases = setOf(FunctionId.VOLUME_DOWN)
            ),
            MirrorAction(
                token = MirrorActionToken.VOLUME_MUTE,
                functionId = FunctionId.VOLUME_MUTE,
                slot = MirrorSlot.RIGHT
            ),
            MirrorAction(
                token = MirrorActionToken.OPEN_ROOT_MENU,
                functionId = FunctionId.MENU,
                slot = MirrorSlot.HEADER
            )
        )
    )

    override val invalidations: Flow<Unit> = redrawOn(currentMessage, canReply)

    /**
     * Both halves of the one question: there is a message, and the notification that brought it
     * offered a way back. Read by the press and by the mirror zone, so neither can be the more
     * generous of the two.
     */
    private fun canReplyNow(): Boolean = currentMessage.value != null && canReply.value

    override fun onInput(functionId: FunctionId): NavigationAction = when (functionId) {
        FunctionId.BACK -> {
            messageSource?.yieldScreen()
            NavigationAction.Pop
        }

        FunctionId.MENU -> {
            messageSource?.yieldScreen()
            NavigationAction.Reset
        }

        FunctionId.SELECT -> {
            if (canReplyNow()) {
                Log.d("Opening the dictation for the message on screen")
                NavigationAction.Next(replyPage)
            } else {
                NavigationAction.None
            }
        }

        FunctionId.PREVIOUS, FunctionId.VOLUME_UP -> {
            volumeSource?.raise()
            NavigationAction.None
        }

        FunctionId.NEXT, FunctionId.VOLUME_DOWN -> {
            volumeSource?.lower()
            NavigationAction.None
        }

        FunctionId.VOLUME_MUTE -> {
            volumeSource?.toggleMute()
            NavigationAction.None
        }

        else -> NavigationAction.None
    }
}
