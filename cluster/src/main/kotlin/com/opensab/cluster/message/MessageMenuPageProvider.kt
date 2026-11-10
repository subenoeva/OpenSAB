package com.opensab.cluster.message

import com.opensab.cluster.CuingPage
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.WorkingPage
import com.opensab.cluster.call.DialSeed
import com.opensab.cluster.cueEmitter
import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.ClusterToken
import com.opensab.domain.audio.Cue
import com.opensab.domain.message.MessageMenuAccess
import com.opensab.domain.message.MessageMenuGates
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.speech.SpeechWord
import com.opensab.logging.Log
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import com.opensab.sab.session.coroutines.runCatchingCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * The two ways of starting an outbound message from the handlebar.
 *
 * `VOICE` first because it is what the rider uses to compose while riding; `FAVORITES` second
 * because it works without speech recognition.
 */
internal enum class MessageMenuItem(val title: ClusterToken, val iconId: IconId) {
    VOICE(ClusterToken.MESSAGE_MENU_BY_VOICE, IconId.MIC),
    FAVORITES(ClusterToken.MESSAGE_MENU_FAVORITES, IconId.FAVORITE_CONTACT)
}

/** What the `MESSAGE` entry of the carousel opens while no message is unread: two rows and a cursor. */
class MessageMenuPageProvider(
    private val access: MessageMenuAccess,
    private val selectContactPageFactory: (DialSeed) -> PageProvider,
    private val copy: ClusterTextResolver
) : WorkingPage, CuingPage, SpeakingPage {

    override val name: String = "MessageMenu"

    private val _cues = cueEmitter()

    /** MOVE when the cursor moved, EDGE when it did not, the same rule as the carousel. */
    override val cues: Flow<Cue> = _cues.asSharedFlow()

    /** A cursor over two rows, like the carousel's. Coming back keeps it. */
    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

    private val items: List<MessageMenuItem> = MessageMenuItem.entries

    private val cursor = MutableStateFlow(0)

    val selectionIndex: Int
        get() = cursor.value

    /** Owned here, written only by [work]. Closed until proven open. */
    private val _gates = MutableStateFlow<MessageMenuGates?>(null)

    override val speech: Flow<List<SpeechRequest>> = flow {
        var isFirst = true
        combine(cursor, _gates.filterNotNull()) { index, gates ->
            val list = mutableListOf<SpeechRequest>()
            if (isFirst) {
                list += SpeechRequest(SpeechToken.MENU_TITLE, SpeechArg.Word(SpeechWord.MENU_MESSAGES))
                isFirst = false
            }
            list += speechForRow(items[index], gates)
            list
        }.collect { emit(it) }
    }

    /**
     * Owned here, emitted only by [work]. Not `redrawOn(_gates)`: the engine launches the redraw
     * collector and the work side by side, and gates that arrive before the collector subscribes
     * would be the replayed value it drops, leaving the frame painted at mount, rows shut, on the
     * cluster.
     */
    private val _redraws = MutableSharedFlow<Unit>()

    override val invalidations: Flow<Unit> = _redraws.asSharedFlow()

    override suspend fun work(): NavigationAction {
        val before = _gates.value
        val gates = runCatchingCancellable { access.gates() }.getOrDefault(MessageMenuGates.CLOSED)
        _gates.value = gates
        Log.d("Message menu gates: %s", gates)
        if (gates != before) {
            _redraws.subscriptionCount.first { it > 0 }
            _redraws.emit(Unit)
        }
        awaitCancellation()
    }

    /**
     * Whether the row under the cursor leads anywhere.
     *
     * The arrow, the press and the OPEN zone of the mirror all read this one property.
     */
    private val canEnter: Boolean
        get() {
            val currentGates = _gates.value ?: MessageMenuGates.CLOSED
            return when (items[selectionIndex]) {
                MessageMenuItem.VOICE -> currentGates.voice
                MessageMenuItem.FAVORITES -> currentGates.favorites.isNotEmpty()
            }
        }

    override fun render(requestId: Int): ProjectionFrame {
        val item = items[selectionIndex]
        val upIcon = if (selectionIndex > 0) IconId.MENU_ARROW_UP else IconId.IC_NONE
        val downIcon =
            if (selectionIndex < items.lastIndex) IconId.MENU_ARROW_DOWN else IconId.IC_NONE
        val rightIcon = if (canEnter) IconId.MENU_ARROW_RIGHT else IconId.IC_NONE

        return ProjectionFrame.MenuScreen(
            requestId = requestId,
            headerBgColorId = ColorId.BLUE,
            headerText = copy.resolve(ClusterToken.HEADER_MESSAGES),
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.BLUE_LIGHT,
            upActionIconId = upIcon,
            upActionIconTintColorId = ARROW_TINT.takeIf { upIcon != IconId.IC_NONE },
            downActionIconId = downIcon,
            downActionIconTintColorId = ARROW_TINT.takeIf { downIcon != IconId.IC_NONE },
            leftActionIconId = IconId.MENU_ARROW_LEFT,
            leftActionIconTintColorId = ARROW_TINT,
            rightActionIconId = rightIcon,
            rightActionIconTintColorId = ARROW_TINT.takeIf { rightIcon != IconId.IC_NONE },
            contentIconId = item.iconId,
            contentIconTintColorId = CONTENT_ICON_TINT,
            contentTextLine1 = copy.resolve(item.title),
            contentTextColorIdLine1 = ColorId.PURE_WHITE,
            contentTextLine2 = "",
            contentTextColorIdLine2 = null
        )
    }

    override fun mirror(): MirrorPage = mirrorPageOf(
        name,
        render(MIRROR_REQUEST_ID),
        actions = listOf(
            MirrorAction(MirrorActionToken.CAROUSEL_PREVIOUS, FunctionId.PREVIOUS, MirrorSlot.UP),
            MirrorAction(MirrorActionToken.CAROUSEL_NEXT, FunctionId.NEXT, MirrorSlot.DOWN),
            MirrorAction(
                token = MirrorActionToken.OPEN,
                functionId = FunctionId.SELECT,
                slot = MirrorSlot.PRIMARY,
                enabled = canEnter
            ),
            MirrorAction(MirrorActionToken.GO_BACK, FunctionId.BACK, MirrorSlot.LEFT),
            MirrorAction(MirrorActionToken.OPEN_ROOT_MENU, FunctionId.MENU, MirrorSlot.HEADER)
        )
    )

    override fun onInput(functionId: FunctionId): NavigationAction = when (functionId) {
        FunctionId.PREVIOUS -> {
            moveCursor(-1)
            NavigationAction.Invalidate
        }

        FunctionId.NEXT -> {
            moveCursor(+1)
            NavigationAction.Invalidate
        }

        FunctionId.SELECT ->
            if (canEnter) NavigationAction.Next(selectContactPageFactory(seedOf(items[selectionIndex])))
            else NavigationAction.None

        FunctionId.BACK -> NavigationAction.Pop

        FunctionId.MENU -> NavigationAction.Reset

        else -> NavigationAction.None
    }

    private fun moveCursor(step: Int) {
        val before = cursor.value
        val after = (before + step).coerceIn(0, items.lastIndex)
        if (after != before) {
            cursor.value = after
        }
        _cues.tryEmit(if (after == before) Cue.EDGE else Cue.MOVE)
    }

    private fun seedOf(item: MessageMenuItem): DialSeed {
        val currentGates = _gates.value ?: MessageMenuGates.CLOSED
        return when (item) {
            MessageMenuItem.VOICE -> DialSeed.Voice
            MessageMenuItem.FAVORITES -> DialSeed.Favorites(currentGates.favorites)
        }
    }

    private fun speechForRow(item: MessageMenuItem, gates: MessageMenuGates): SpeechRequest = when (item) {
        MessageMenuItem.VOICE ->
            if (gates.voice) SpeechRequest(SpeechToken.MESSAGE_MENU_VOICE)
            else SpeechRequest(SpeechToken.MESSAGE_MENU_VOICE_DISABLED)
        MessageMenuItem.FAVORITES ->
            if (gates.favorites.isNotEmpty()) SpeechRequest(SpeechToken.MESSAGE_MENU_FAVORITES)
            else SpeechRequest(SpeechToken.MESSAGE_MENU_FAVORITES_EMPTY)
    }

    private companion object {
        val ARROW_TINT = ColorId.PURE_BLACK
        val CONTENT_ICON_TINT = ColorId.PURE_WHITE
    }
}
