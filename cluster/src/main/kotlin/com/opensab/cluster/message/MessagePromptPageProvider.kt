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
import com.opensab.logging.Log
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * The page that asks before reading: a message arrived, and two ways out.
 *
 * It sits **in front of** [MessagePageProvider], not instead of it. `SELECT` pushes the reading on
 * top; `BACK` and `MENU` give the screen back with the message still unread, instead of the body
 * landing on the cluster unannounced.
 *
 * **The carousel does not reach this page**: its `MESSAGE` entry opens the reading directly, and
 * asking "shall I read it?" at the door of the compose menu would make no sense.
 */
class MessagePromptPageProvider(
    private val currentMessage: StateFlow<MessageInfo?>,
    private val copy: ClusterTextResolver,
    private val messageSource: MessageSource? = null,
    private val volumeSource: VolumeSource? = null,
    readPageFactory: () -> PageProvider
) : SpeakingPage {

    override val name: String = "MessagePromptPage"

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

    /**
     * One instance for the whole session, resolved on the first press.
     *
     * The reading is a page the carousel also reaches, so handing over a fresh one here would give
     * one message two identities and turn the next message of the thread into a second mount
     * instead of a redraw.
     */
    private val readPage: PageProvider by lazy(readPageFactory)

    /**
     * The message the rider has already been asked about.
     *
     * Owned by this page and by nothing else, and safe as a plain `var` because
     * [BaseStateManager] holds the only collector of [speech] and cancels it before starting
     * another (see [SpeakingPage]).
     *
     * It exists because "ask once" is once **per message**, not once per mount.
     * `WeatherPageProvider` gets away with a session-wide flag: its forecast is the same forecast
     * whenever the page comes back. Here the same flag would silence the second message of the
     * thread, and a remount — which is what coming back from a call is — would re-ask about a
     * message the rider already heard about.
     */
    private var askedAbout: MessageInfo? = null

    /**
     * The question, once per message.
     *
     * Built on the message flow rather than on the mount, so a message arriving while the page is
     * already up is asked about too: that path never restarts the voice job, it only invalidates
     * the frame.
     */
    override val speech: Flow<List<SpeechRequest>> = currentMessage
        .filterNotNull()
        .filter { it != askedAbout }
        .onEach { askedAbout = it }
        .map { listOf(askToListen(it)) }

    /**
     * Two phrasings and not one, because [SpeechRequest] checks arity on construction and
     * `MessageInfo.appName` is nullable: `MessagingPackages.labelOf` has no entry outside its
     * thirteen apps. Naming the app is what the rider asked for; not having one is a shorter
     * sentence, never a crash and never "Message from Carlos on . Shall I read it?".
     */
    private fun askToListen(message: MessageInfo): SpeechRequest {
        val defaultSender = copy.resolve(ClusterToken.HEADER_MESSAGES)
        val sender = SpeechArg.Text(message.sender.ifEmpty { defaultSender })
        val appName = message.appName?.takeIf { it.isNotEmpty() }
        return if (appName != null) {
            SpeechRequest(SpeechToken.MESSAGE_PROMPT, sender, SpeechArg.Text(appName))
        } else {
            SpeechRequest(SpeechToken.MESSAGE_PROMPT_SHORT, sender)
        }
    }

    override fun render(requestId: Int): ProjectionFrame {
        val header = copy.resolve(ClusterToken.HEADER_MESSAGES)
        val message = currentMessage.value ?: return LiveScreenPresets.messageStandby(
            requestId = requestId,
            header = header,
            empty = copy.resolve(ClusterToken.EMPTY_MESSAGES)
        )
        return LiveScreenPresets.messagePrompt(
            requestId = requestId,
            header = header,
            sender = message.sender.ifEmpty { header },
            appName = message.appName.orEmpty()
        )
    }

    /**
     * The shared content-page block, with the two this page earns:
     *
     * - `BACK` carries [MirrorActionToken.DISMISS_MESSAGE] rather than `GO_BACK`, because the
     *   branch yields before popping — the same reason [MessagePageProvider] does.
     * - `SELECT` carries [MirrorActionToken.READ_MESSAGE], and **it is declared even on standby**,
     *   disabled. Without a message the branch leads nowhere, and a zone that holds its slot saying
     *   so beats one that vanishes and shifts the rest under a glance — the shape `ACCEPT_CALL`
     *   already takes outside RINGING.
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
                token = MirrorActionToken.READ_MESSAGE,
                functionId = FunctionId.SELECT,
                slot = MirrorSlot.PRIMARY,
                enabled = currentMessage.value != null
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

    override val invalidations: Flow<Unit> = redrawOn(currentMessage)

    override fun onInput(functionId: FunctionId): NavigationAction = when (functionId) {
        FunctionId.BACK -> {
            Log.d("Message prompt ignored, the message stays unread")
            messageSource?.yieldScreen()
            NavigationAction.Pop
        }

        FunctionId.MENU -> {
            messageSource?.yieldScreen()
            NavigationAction.Reset
        }

        FunctionId.SELECT -> {
            if (currentMessage.value != null) {
                Log.d("Message prompt accepted, opening the reading")
                NavigationAction.Next(readPage)
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
