package com.opensab.cluster.rootmenu

import com.opensab.cluster.CuingPage
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.PageProvider
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.cueEmitter
import com.opensab.cluster.icons.iconId
import com.opensab.cluster.icons.toProjectionIconId
import com.opensab.cluster.message.messageEntryPage
import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.cluster.music.musicEntryPage
import com.opensab.cluster.redrawOn
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.ClusterToken
import com.opensab.cluster.weather.speechWord
import com.opensab.domain.audio.Cue
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallStatus
import com.opensab.domain.message.MessageInfo
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.settings.RootMenuEntry
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechQuantity
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.weather.WeatherInfo
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.IconId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * `headerBgColorId` fills the header and `bodyBgColorId` the body. Neither may be PURE_WHITE or
 * PURE_BLACK — those are the cluster's two fixed colours, the ones the
 * assignment deliberately leaves out of the palette, and a body painted with PURE_BLACK is a
 * screen with no colour at all.
 */
enum class RootMenuItem(
    val title: ClusterToken,
    val iconId: IconId,
    val headerBgColorId: ColorId,
    val bodyBgColorId: ColorId
) {
    WEATHER(ClusterToken.HEADER_WEATHER, IconId.WEATHER_SUN_AND_CLOUD, ColorId.GREY, ColorId.GREY_LIGHT),
    NAVIGATION(ClusterToken.HEADER_NAVIGATION, IconId.NAVIGATION, ColorId.GREEN, ColorId.GREEN_LIGHT),
    CALL(ClusterToken.HEADER_PHONE, IconId.CALL, ColorId.PURPLE, ColorId.PURPLE_LIGHT),
    MESSAGE(ClusterToken.HEADER_MESSAGES, IconId.MESSAGE, ColorId.BLUE, ColorId.BLUE_LIGHT),
    MUSIC(ClusterToken.HEADER_MUSIC, IconId.MUSIC, ColorId.ORANGE, ColorId.ORANGE_LIGHT),
    VOLUME(ClusterToken.HEADER_VOLUME, IconId.VOLUME, ColorId.GREY, ColorId.GREY_LIGHT)
}

/**
 * The one translation from the settings' name to the engine's entry: a name added without an item
 * does not compile.
 */
internal fun rootMenuItemOf(entry: RootMenuEntry): RootMenuItem = when (entry) {
    RootMenuEntry.WEATHER -> RootMenuItem.WEATHER
    RootMenuEntry.NAVIGATION -> RootMenuItem.NAVIGATION
    RootMenuEntry.CALL -> RootMenuItem.CALL
    RootMenuEntry.MESSAGE -> RootMenuItem.MESSAGE
    RootMenuEntry.MUSIC -> RootMenuItem.MUSIC
    RootMenuEntry.VOLUME -> RootMenuItem.VOLUME
}

/**
 * Where the rider is on the carousel. [item] is `null` until the first move, which means "the first
 * entry, whichever it is now"; after that the focus follows the entry, and [index] is where it was
 * the last time it moved, so an entry that vanishes hands over to its neighbour. [seq] only grows,
 * on MENU, so the same entry can be announced twice in a row.
 */
internal data class RootMenuFocus(val item: RootMenuItem?, val index: Int, val seq: Long) {

    fun indexIn(list: List<RootMenuItem>): Int {
        val at = item?.let(list::indexOf) ?: -1
        return if (at >= 0) at else index.coerceIn(0, list.lastIndex)
    }
}

private fun itemsOf(entries: List<RootMenuEntry>): List<RootMenuItem> =
    entries.map(::rootMenuItemOf).ifEmpty { RootMenuItem.entries }

/**
 * Root page mounted when the cluster switches to SAB mode: a vertical carousel list of features
 * rendered as a `MYSTERY_BOX` frame with `BodyType.MENU`.
 *
 * @param entries the visible entries in the rider's order, owned by the graph. Read on every frame,
 *   because the rider can rearrange them while this page is on the cluster.
 * @param pageFactory the page an entry opens. Total by construction: every entry of the carousel
 *   opens something, and an entry added without wiring one fails to compile rather than appearing
 *   as a zone that leads nowhere.
 *
 *   Called from exactly one place, the SELECT branch of [onInput], and only when [canEnter] already
 *   said yes. Nothing on the projection path builds a page to answer a question.
 */
class RootMenuPageProvider(
    private val entries: StateFlow<List<RootMenuEntry>>,
    private val musicState: StateFlow<MusicState>? = null,
    private val navInstruction: StateFlow<NavInstruction?>? = null,
    private val activeCall: StateFlow<CallInfo?>? = null,
    private val currentWeather: StateFlow<WeatherInfo?>? = null,
    private val currentMessage: StateFlow<MessageInfo?>? = null,
    private val pageFactory: (RootMenuItem) -> PageProvider,
    /** No default: one would be a page painting English because the graph forgot. */
    private val copy: ClusterTextResolver,
    private val musicLoadingPageFactory: (() -> PageProvider)? = null,
    private val messageMenuPageFactory: (() -> PageProvider)? = null
) : CuingPage, SpeakingPage {

    override val name: String = "RootMenu"

    private val _cues = cueEmitter()

    /** MOVE when the cursor moved, EDGE when it did not — which is the one thing the state cannot
     *  tell you, because a cursor stopped by `coerce` writes the index it already had. */
    override val cues: Flow<Cue> = _cues.asSharedFlow()

    /**
     * The one cursor of this page, written only by [onInput]. The frame and the voice both resolve
     * it against the live [entries], so the cluster and the helmet can never name different entries.
     */
    private val focus = MutableStateFlow(RootMenuFocus(item = null, index = 0, seq = 0L))

    override val speech: Flow<List<SpeechRequest>> = combine(focus, entries) { focus, entryList ->
        val list = itemsOf(entryList)
        list[focus.indexIn(list)] to focus.seq
    }.distinctUntilChanged().map { (item, _) ->
        listOf(speechFor(item))
    }

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

    /**
     * The carousel as it stands right now. The graph never hands over an empty list; if one ever
     * arrived, the factory list keeps the cluster from painting nothing.
     */
    private val items: List<RootMenuItem>
        get() = itemsOf(entries.value)

    val selectionIndex: Int
        get() = focus.value.indexIn(items)

    val selectedItem: RootMenuItem
        get() = items[selectionIndex]

    /**
     * The arrow, the press and the OPEN zone of the mirror all read this one property, so a source
     * lighting up opens all three at once and going quiet closes all three. An arrow that says "no
     * way through" over a button that goes through anyway is the cluster and the handlebar
     * disagreeing.
     *
     * Weather is always open: the weather page is where SELECT asks for a fresh reading, so closing
     * the entry until one arrived left a rider with no fix at session start no way to get one.
     */
    private val canEnter: Boolean
        get() = when (selectedItem) {
            // If a compose menu is wired, the entry is permanently open. If not wired, it requires an unread message to enter the reader.
            RootMenuItem.MESSAGE -> currentMessage?.value != null || messageMenuPageFactory != null
            else -> true
        }

    override fun render(requestId: Int): ProjectionFrame {
        val item = selectedItem

        val preferredIcon = when (item) {
            RootMenuItem.NAVIGATION -> {
                val instruction = navInstruction?.value
                instruction?.maneuver?.toProjectionIconId() ?: item.iconId
            }
            RootMenuItem.WEATHER -> {
                // Falls back to NO_WEATHER, not the static feature icon, while the forecast is
                // unavailable.
                val weather = currentWeather?.value
                weather?.type?.iconId ?: IconId.NO_WEATHER
            }
            RootMenuItem.CALL -> {
                val call = activeCall?.value
                if (call != null && (call.status == CallStatus.ACTIVE || call.status == CallStatus.RINGING)) {
                    IconId.IC_NONE
                } else {
                    item.iconId
                }
            }
            RootMenuItem.MUSIC -> {
                val isPlaying = musicState?.value?.status == PlaybackStatus.PLAYING
                val hasTrack = !musicState?.value?.metadata?.title.isNullOrEmpty()
                when {
                    isPlaying && hasTrack -> IconId.IC_NONE
                    isPlaying -> IconId.ACTIVE_MUSIC
                    else -> item.iconId
                }
            }
            else -> item.iconId
        }

        val line1 = when (item) {
            RootMenuItem.MUSIC -> {
                val state = musicState?.value
                if (state != null && state.status == PlaybackStatus.PLAYING) {
                    state.metadata.title
                } else {
                    ""
                }
            }
            RootMenuItem.WEATHER -> {
                val weather = currentWeather?.value
                if (weather != null) "${weather.temperature}${weather.temperatureUnit}" else ""
            }
            RootMenuItem.NAVIGATION -> {
                val instruction = navInstruction?.value
                instruction?.distanceFormatted?.ifEmpty { instruction.title } ?: ""
            }
            RootMenuItem.CALL -> {
                val call = activeCall?.value
                if (call != null && (call.status == CallStatus.ACTIVE || call.status == CallStatus.RINGING)) {
                    call.callerName.ifEmpty {
                        call.callerNumber.ifEmpty { copy.resolve(ClusterToken.HEADER_PHONE) }
                    }
                } else {
                    ""
                }
            }
            RootMenuItem.MESSAGE -> currentMessage?.value?.sender ?: ""
            RootMenuItem.VOLUME -> ""
        }

        val line2 = when (item) {
            RootMenuItem.MUSIC -> {
                val state = musicState?.value
                if (state != null && state.status == PlaybackStatus.PLAYING) {
                    state.metadata.artist
                } else {
                    ""
                }
            }
            RootMenuItem.WEATHER -> ""
            RootMenuItem.NAVIGATION -> ""
            RootMenuItem.CALL -> {
                val call = activeCall?.value
                when (call?.status) {
                    CallStatus.RINGING -> copy.resolve(ClusterToken.CALL_RINGING)
                    CallStatus.ACTIVE -> copy.resolve(ClusterToken.CALL_IN_CALL)
                    else -> ""
                }
            }
            RootMenuItem.MESSAGE -> currentMessage?.value?.body ?: ""
            RootMenuItem.VOLUME -> ""
        }

        // The MENU body follows the same rule as the live one: an entry sends **either** a content
        // icon **or** a second line, never both, and the BTU answers the pair with `status=0x0C` at
        // the `contentIconId` byte. The call and music entries already dropped their icon by hand where they knew they
        // would fill line 2; this is the same decision taken once, where the second line is
        // actually known, so no entry can grow a subtitle and take the icon down with it.
        //
        // Measured on the message entry in the field — eight frames, every one rejected at
        // `errorPosition=22` — and the rider saw the entry go missing from the carousel while the
        // mirror, published before the verdict, showed it intact.
        val contentIcon = if (line2.isNotEmpty()) IconId.IC_NONE else preferredIcon

        // A tint byte only reaches the wire when its icon is present, so the pairs must stay
        // consistent or every field after them shifts (`FrameEncoder.encodeMenuScreen`).
        val upActionIconId = if (selectionIndex > 0) IconId.MENU_ARROW_UP else IconId.IC_NONE
        val downActionIconId =
            if (selectionIndex < items.lastIndex) IconId.MENU_ARROW_DOWN else IconId.IC_NONE
        val rightActionIconId = if (canEnter) IconId.MENU_ARROW_RIGHT else IconId.IC_NONE

        return ProjectionFrame.MenuScreen(
            requestId = requestId,
            headerBgColorId = item.headerBgColorId,
            headerText = copy.resolve(item.title),
            headerTextColorId = ColorId.PURE_WHITE,
            bodyBgColorId = item.bodyBgColorId,
            upActionIconId = upActionIconId,
            upActionIconTintColorId = ARROW_TINT.takeIf { upActionIconId != IconId.IC_NONE },
            downActionIconId = downActionIconId,
            downActionIconTintColorId = ARROW_TINT.takeIf { downActionIconId != IconId.IC_NONE },
            leftActionIconId = IconId.IC_NONE,
            leftActionIconTintColorId = null,
            rightActionIconId = rightActionIconId,
            rightActionIconTintColorId = ARROW_TINT.takeIf { rightActionIconId != IconId.IC_NONE },
            contentIconId = contentIcon,
            contentIconTintColorId = CONTENT_ICON_TINT.takeIf { contentIcon != IconId.IC_NONE },
            contentTextLine1 = line1,
            contentTextColorIdLine1 = if (line1.isNotEmpty()) ColorId.PURE_WHITE else null,
            contentTextLine2 = line2,
            contentTextColorIdLine2 = if (line2.isNotEmpty()) ColorId.GREY_LIGHT else null
        )
    }

    /**
     * The carousel offers four zones, one per branch of [onInput]. There is no volume block: this page takes no `VolumeSource`, so `VOLUME_UP`,
     * `VOLUME_DOWN` and `VOLUME_MUTE` all fall into the `else` branch and return `None` — a volume
     * zone here would be a control the rider can touch that does nothing.
     *
     * `OPEN` is the only action this page can hand over disabled, and [canEnter] is the whole of it:
     * the arrow, the press and this zone read that one property, so a source going quiet closes the
     * three together. Building a page to find out would be answering a question by doing work, and
     * this method runs on every projected frame.
     */
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
            MirrorAction(MirrorActionToken.OPEN_ROOT_MENU, FunctionId.MENU, MirrorSlot.HEADER)
        )
    )

    override val invalidations: Flow<Unit> = redrawOn(
        *listOfNotNull(entries, musicState, navInstruction, activeCall, currentWeather, currentMessage)
            .toTypedArray()
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
            if (canEnter) {
                val targetPage = when (selectedItem) {
                    RootMenuItem.MUSIC if musicLoadingPageFactory != null && musicState != null -> {
                        musicEntryPage(
                            state = musicState.value,
                            livePage = { pageFactory(RootMenuItem.MUSIC) },
                            loadingPage = { musicLoadingPageFactory.invoke() }
                        )
                    }
                    RootMenuItem.MESSAGE if messageMenuPageFactory != null -> {
                        messageEntryPage(
                            currentMessage = currentMessage?.value,
                            readPage = { pageFactory(RootMenuItem.MESSAGE) },
                            menuPage = { messageMenuPageFactory.invoke() }
                        )
                    }
                    else -> pageFactory(selectedItem)
                }
                NavigationAction.Next(targetPage)
            } else {
                NavigationAction.None
            }

        FunctionId.MENU -> {
            focus.update { it.copy(seq = it.seq + 1) }
            NavigationAction.Reset
        }

        else -> NavigationAction.None
    }

    private fun moveCursor(step: Int) {
        val list = items
        val before = selectionIndex
        val after = (before + step).coerceIn(0, list.lastIndex)
        // Written at the edge too: that anchors a focus still on "the first entry" to the entry
        // itself, and the voice stays quiet because the entry it resolves to did not change.
        focus.update { it.copy(item = list[after], index = after) }
        _cues.tryEmit(if (after == before) Cue.EDGE else Cue.MOVE)
    }

    private fun speechFor(item: RootMenuItem): SpeechRequest = when (item) {
        RootMenuItem.WEATHER -> {
            val weather = currentWeather?.value
            if (weather != null) {
                SpeechRequest(
                    SpeechToken.ROOT_WEATHER,
                    SpeechArg.Word(weather.type.speechWord()),
                    SpeechArg.Quantity(SpeechQuantity.DEGREES, weather.temperature)
                )
            } else {
                SpeechRequest(SpeechToken.ROOT_WEATHER_EMPTY)
            }
        }

        RootMenuItem.NAVIGATION -> {
            val instruction = navInstruction?.value
            if (instruction != null) {
                SpeechRequest(SpeechToken.ROOT_NAVIGATION_GUIDING)
            } else {
                SpeechRequest(SpeechToken.ROOT_NAVIGATION)
            }
        }

        RootMenuItem.CALL -> SpeechRequest(SpeechToken.ROOT_CALL)

        RootMenuItem.MESSAGE -> {
            val message = currentMessage?.value
            if (message != null) {
                SpeechRequest(SpeechToken.ROOT_MESSAGE_UNREAD, SpeechArg.Text(message.sender))
            } else {
                SpeechRequest(SpeechToken.ROOT_MESSAGE)
            }
        }

        RootMenuItem.MUSIC -> {
            val music = musicState?.value
            if (music != null && music.status == PlaybackStatus.PLAYING) {
                SpeechRequest(SpeechToken.ROOT_MUSIC_PLAYING, SpeechArg.Text(music.metadata.title))
            } else {
                SpeechRequest(SpeechToken.ROOT_MUSIC)
            }
        }

        RootMenuItem.VOLUME -> SpeechRequest(SpeechToken.ROOT_VOLUME)
    }

    private companion object {
        val ARROW_TINT = ColorId.PURE_BLACK

        val CONTENT_ICON_TINT = ColorId.PURE_WHITE
    }
}
