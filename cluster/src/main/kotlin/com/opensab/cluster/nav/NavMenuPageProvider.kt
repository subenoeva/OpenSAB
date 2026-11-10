package com.opensab.cluster.nav

import com.opensab.cluster.CuingPage
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
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
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.speech.SpeechWord
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * Menu displayed when the rider enters NAVIGATION without an active guidance route.
 *
 * Speaks its title and row on mount; on selecting voice search, page teardown stops the speech
 * before search opens the microphone.
 */
class NavMenuPageProvider(
    private val voicePageFactory: () -> PageProvider,
    private val copy: ClusterTextResolver
) : PageProvider, CuingPage, SpeakingPage {

    override val name: String = "NavMenu"

    private val _cues = cueEmitter()
    override val cues: Flow<Cue> = _cues.asSharedFlow()

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

    private val items: List<NavMenuItem> = NavMenuItem.entries

    private val cursor = MutableStateFlow(0)

    val selectionIndex: Int
        get() = cursor.value

    override val speech: Flow<List<SpeechRequest>> = flow {
        var isFirst = true
        cursor.collect {
            val list = mutableListOf<SpeechRequest>()
            if (isFirst) {
                list += SpeechRequest(SpeechToken.MENU_TITLE, SpeechArg.Word(SpeechWord.MENU_NAVIGATION))
                isFirst = false
            }
            list += SpeechRequest(SpeechToken.NAV_MENU_VOICE)
            emit(list)
        }
    }

    override val invalidations: Flow<Unit> = emptyFlow()

    override fun render(requestId: Int): ProjectionFrame {
        val item = items[selectionIndex]
        val upIcon = if (selectionIndex > 0) IconId.MENU_ARROW_UP else IconId.IC_NONE
        val downIcon =
            if (selectionIndex < items.lastIndex) IconId.MENU_ARROW_DOWN else IconId.IC_NONE

        return ProjectionFrame.MenuScreen(
            requestId = requestId,
            headerBgColorId = ColorId.GREEN,
            headerText = copy.resolve(ClusterToken.HEADER_NAVIGATION),
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = ColorId.GREEN_LIGHT,
            upActionIconId = upIcon,
            upActionIconTintColorId = ARROW_TINT.takeIf { upIcon != IconId.IC_NONE },
            downActionIconId = downIcon,
            downActionIconTintColorId = ARROW_TINT.takeIf { downIcon != IconId.IC_NONE },
            leftActionIconId = IconId.MENU_ARROW_LEFT,
            leftActionIconTintColorId = ARROW_TINT,
            rightActionIconId = IconId.MENU_ARROW_RIGHT,
            rightActionIconTintColorId = ARROW_TINT,
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
            MirrorAction(
                token = MirrorActionToken.CAROUSEL_PREVIOUS,
                functionId = FunctionId.PREVIOUS,
                slot = MirrorSlot.UP,
                aliases = setOf(FunctionId.VOLUME_UP)
            ),
            MirrorAction(
                token = MirrorActionToken.CAROUSEL_NEXT,
                functionId = FunctionId.NEXT,
                slot = MirrorSlot.DOWN,
                aliases = setOf(FunctionId.VOLUME_DOWN)
            ),
            MirrorAction(
                token = MirrorActionToken.OPEN,
                functionId = FunctionId.SELECT,
                slot = MirrorSlot.PRIMARY,
                enabled = true
            ),
            MirrorAction(MirrorActionToken.GO_BACK, FunctionId.BACK, MirrorSlot.LEFT),
            MirrorAction(MirrorActionToken.OPEN_ROOT_MENU, FunctionId.MENU, MirrorSlot.HEADER)
        )
    )

    override fun onInput(functionId: FunctionId): NavigationAction = when (functionId) {
        FunctionId.PREVIOUS, FunctionId.VOLUME_UP -> {
            moveCursor(-1)
            NavigationAction.Invalidate
        }

        FunctionId.NEXT, FunctionId.VOLUME_DOWN -> {
            moveCursor(+1)
            NavigationAction.Invalidate
        }

        FunctionId.SELECT -> NavigationAction.Next(voicePageFactory())

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

    private companion object {
        val ARROW_TINT = ColorId.PURE_BLACK
        val CONTENT_ICON_TINT = ColorId.PURE_WHITE
    }
}
