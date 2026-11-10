package com.opensab.cluster.call

import com.opensab.cluster.CuingPage
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.WorkingPage
import com.opensab.cluster.cueEmitter
import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.cluster.redrawOn
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.ClusterToken
import com.opensab.domain.audio.Cue
import com.opensab.domain.contacts.CallMenuAccess
import com.opensab.domain.contacts.CallMenuGates
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow

/**
 * The two ways of starting a call from the handlebar.
 *
 * `VOICE` first because it is what the rider asked for and what he uses when he does not know the
 * number by heart; `FAVORITES` second because it is the one that works with the microphone denied,
 * broken or unproven.
 */
internal enum class CallMenuItem(val title: ClusterToken, val iconId: IconId) {
    VOICE(ClusterToken.CALL_MENU_BY_VOICE, IconId.MIC),
    FAVORITES(ClusterToken.CALL_MENU_FAVORITES, IconId.FAVORITE_CONTACT)
}

/**
 * What the `CALL` entry of the carousel opens while no call is up: two rows and a cursor.
 *
 * It is a [WorkingPage] for one reason, and it is a question of ownership. Its two rows open on
 * facts nobody else holds — two permissions and whether the rider starred anybody — and one of those
 * is an address book read, which suspends, while `render` does not. The engine already owns the
 * lifetime of a mounted page's work, so the read happens in [work] and its result lands in a flow
 * this page owns: **the gates are re-read on every mount**, which is what makes granting the
 * permission in Settings and coming back Just Work, with no permission observer anywhere.
 *
 * There is no volume block, exactly as on the carousel: this page takes no `VolumeSource`, so the
 * volume keys fall into the `else` branch and a volume zone here would be a control that does
 * nothing.
 */
class CallMenuPageProvider(
    private val access: CallMenuAccess,
    /**
     * The dialler a row opens. A **new instance per press**, because the page carries the seed and
     * discards its state on resume: a memoized one would come back showing the face of the last
     * attempt.
     */
    private val dialPageFactory: (DialSeed) -> PageProvider,
    private val copy: ClusterTextResolver
) : WorkingPage, CuingPage, SpeakingPage {

    override val name: String = "CallMenu"

    private val _cues = cueEmitter()

    /** MOVE when the cursor moved, EDGE when it did not, the same rule as the carousel. */
    override val cues: Flow<Cue> = _cues.asSharedFlow()

    /** A cursor over two rows, like the carousel's. Coming back from the dialler keeps it. */
    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

    private val items: List<CallMenuItem> = CallMenuItem.entries

    private val cursor = MutableStateFlow(0)

    val selectionIndex: Int
        get() = cursor.value

    /** Owned here, written only by [work]. Closed until proven open. */
    private val _gates = MutableStateFlow<CallMenuGates?>(null)

    override val speech: Flow<List<SpeechRequest>> = flow {
        var isFirst = true
        combine(cursor, _gates.filterNotNull()) { index, gates ->
            val list = mutableListOf<SpeechRequest>()
            if (isFirst) {
                list += SpeechRequest(SpeechToken.MENU_TITLE, SpeechArg.Word(SpeechWord.MENU_PHONE))
                isFirst = false
            }
            list += speechForRow(items[index], gates)
            list
        }.collect { emit(it) }
    }

    override val invalidations: Flow<Unit> = redrawOn(_gates)

    /**
     * One read of the two permissions and the address book, then nothing.
     *
     * It never completes on its own — [awaitCancellation] — because this page has no outcome to
     * navigate to: what ends it is the rider pressing something or leaving, and the engine cancelling
     * the job is exactly what "leaving" means here.
     *
     * **Known hole, measured and left alone:** this page is one instance per session, so `_gates`
     * survives the unmount and the frame `BaseStateManager` renders right after remounting — before
     * this coroutine is even dispatched — paints the gates of the *previous* mount. Shutting them
     * from here does not close it: the reset would land at the same suspension point the fresh read
     * does. Closing it properly needs a synchronous mount hook on `PageProvider`, which is an engine
     * change and not this page's. In practice the stale value can only differ if the address book
     * changed while the rider was away, since revoking a permission kills the process.
     */
    override suspend fun work(): NavigationAction {
        _gates.value = runCatchingCancellable { access.gates() }.getOrDefault(CallMenuGates.CLOSED)
        Log.d("Call menu gates: %s", _gates.value)
        awaitCancellation()
    }

    /**
     * Whether the row under the cursor leads anywhere.
     *
     * The arrow, the press and the OPEN zone of the mirror all read this one property, so a denied
     * permission or an empty favourites list closes the three together — the shape `canEnter` has on
     * the carousel.
     */
    private val canEnter: Boolean
        get() {
            val currentGates = _gates.value ?: CallMenuGates.CLOSED
            return when (items[selectionIndex]) {
                CallMenuItem.VOICE -> currentGates.voice
                CallMenuItem.FAVORITES -> currentGates.favorites.isNotEmpty()
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
            headerBgColorId = ColorId.PURPLE,
            headerText = copy.resolve(ClusterToken.HEADER_PHONE),
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.PURPLE_LIGHT,
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
            if (canEnter) NavigationAction.Next(dialPageFactory(seedOf(items[selectionIndex])))
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

    private fun seedOf(item: CallMenuItem): DialSeed {
        val currentGates = _gates.value ?: CallMenuGates.CLOSED
        return when (item) {
            CallMenuItem.VOICE -> DialSeed.Voice
            CallMenuItem.FAVORITES -> DialSeed.Favorites(currentGates.favorites)
        }
    }

    private fun speechForRow(item: CallMenuItem, gates: CallMenuGates): SpeechRequest = when (item) {
        CallMenuItem.VOICE ->
            if (gates.voice) SpeechRequest(SpeechToken.CALL_MENU_VOICE)
            else SpeechRequest(SpeechToken.CALL_MENU_VOICE_DISABLED)
        CallMenuItem.FAVORITES ->
            if (gates.favorites.isNotEmpty()) SpeechRequest(SpeechToken.CALL_MENU_FAVORITES)
            else SpeechRequest(SpeechToken.CALL_MENU_FAVORITES_EMPTY)
    }

    private companion object {
        val ARROW_TINT = ColorId.PURE_BLACK

        val CONTENT_ICON_TINT = ColorId.PURE_WHITE
    }
}
