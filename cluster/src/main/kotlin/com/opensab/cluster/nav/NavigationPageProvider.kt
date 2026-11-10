package com.opensab.cluster.nav

import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.NavigationAction
import com.opensab.cluster.ResumeBehavior
import com.opensab.cluster.SpeakingPage
import com.opensab.cluster.icons.toProjectionIconId
import com.opensab.cluster.mirror.MIRROR_REQUEST_ID
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.mirrorPageOf
import com.opensab.cluster.redrawOn
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.ClusterToken
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.nav.ManeuverType
import com.opensab.domain.nav.NavInstruction
import com.opensab.domain.nav.NavigationManager
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.domain.speech.SpeechWord
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Live navigation page pushed when the rider selects the navigation entry of the root menu. */
class NavigationPageProvider(
    private val navInstruction: StateFlow<NavInstruction?>,
    /** No default: one would be a page painting English because the graph forgot. */
    private val copy: ClusterTextResolver,
    private val navigationManager: NavigationManager? = null,
    private val volumeSource: VolumeSource? = null
) : SpeakingPage {

    override val name: String = "NavigationPage"

    /** Conflated: a second SELECT while the first repeat is still queued replaces it. */
    private val scripts = Channel<List<SpeechRequest>>(Channel.CONFLATED)

    /** Only SELECT speaks: mounting the page is not a request to hear it. */
    override val speech: Flow<List<SpeechRequest>> = scripts.receiveAsFlow()

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

    override fun render(requestId: Int): ProjectionFrame {
        val instruction =
            navInstruction.value ?: return LiveScreenPresets.navigationStandby(
                requestId = requestId,
                header = copy.resolve(ClusterToken.HEADER_NAVIGATION),
                empty = copy.resolve(ClusterToken.EMPTY_NAVIGATION)
            )
        val maneuverIcon = instruction.maneuver.toProjectionIconId()
        val distanceOrTitle = instruction.distanceFormatted.ifEmpty { instruction.title }
        return LiveScreenPresets.navigation(
            requestId = requestId,
            header = copy.resolve(ClusterToken.HEADER_NAV_SHORT),
            instruction = distanceOrTitle,
            street = instruction.streetName,
            maneuverIcon = maneuverIcon
        )
    }

    /**
     * Six zones, one per branch of [onInput] that acts. The list does not change between guidance and standby: every branch keeps its effect
     * when there is no instruction, so no zone would be honest to drop.
     *
     * `VOLUME_MUTE` takes `RIGHT` because `LEFT` already carries `BACK`, and the two arrows carry
     * the dedicated volume ids as aliases: `onInput` routes `PREVIOUS` and `VOLUME_UP` to the same
     * `raise()`, so declaring them apart would paint two identical zones.
     */
    override fun mirror(): MirrorPage = mirrorPageOf(
        name,
        render(MIRROR_REQUEST_ID),
        actions = listOf(
            MirrorAction(
                token = MirrorActionToken.REPEAT_MANEUVER,
                functionId = FunctionId.SELECT,
                slot = MirrorSlot.PRIMARY
            ),
            MirrorAction(
                token = MirrorActionToken.GO_BACK,
                functionId = FunctionId.BACK,
                slot = MirrorSlot.LEFT
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

    override val invalidations: Flow<Unit> = redrawOn(navInstruction)

    override fun onInput(functionId: FunctionId): NavigationAction = when (functionId) {
        FunctionId.BACK -> NavigationAction.Pop

        FunctionId.MENU -> NavigationAction.Reset

        FunctionId.SELECT -> {
            navInstruction.value?.let { scripts.trySend(it.maneuverScript()) }
            navigationManager?.repeatManeuver()
            NavigationAction.None
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

    /** Distance, turn, street; each its own phrase, so a missing one is skipped cleanly. */
    private fun NavInstruction.maneuverScript(): List<SpeechRequest> = listOfNotNull(
        distanceFormatted.ifEmpty { title }.takeIf { it.isNotBlank() }?.let { SpeechArg.Text(it) },
        maneuver.spoken()?.let { SpeechArg.Word(it) },
        streetName.takeIf { it.isNotBlank() }?.let { SpeechArg.Text(it) }
    ).map { SpeechRequest(SpeechToken.NAV_MANEUVER, it) }

    /** New figures reuse the word of their family: no new phrase for the rider to learn. */
    private fun ManeuverType.spoken(): SpeechWord? = when (this) {
        ManeuverType.STRAIGHT, ManeuverType.DEPART -> SpeechWord.NAV_STRAIGHT
        ManeuverType.TURN_SLIGHT_LEFT, ManeuverType.FORK_LEFT, ManeuverType.RAMP_LEFT -> SpeechWord.NAV_TURN_SLIGHT_LEFT
        ManeuverType.TURN_LEFT -> SpeechWord.NAV_TURN_LEFT
        ManeuverType.TURN_SHARP_LEFT -> SpeechWord.NAV_TURN_SHARP_LEFT
        ManeuverType.TURN_SLIGHT_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.RAMP_RIGHT -> SpeechWord.NAV_TURN_SLIGHT_RIGHT
        ManeuverType.TURN_RIGHT -> SpeechWord.NAV_TURN_RIGHT
        ManeuverType.TURN_SHARP_RIGHT -> SpeechWord.NAV_TURN_SHARP_RIGHT
        ManeuverType.UTURN, ManeuverType.UTURN_RIGHT -> SpeechWord.NAV_UTURN
        ManeuverType.ROUNDABOUT, ManeuverType.ROUNDABOUT_CW,
        ManeuverType.ROUNDABOUT_EXIT, ManeuverType.ROUNDABOUT_EXIT_CW,
        ManeuverType.ROUNDABOUT_SHARP_RIGHT, ManeuverType.ROUNDABOUT_RIGHT,
        ManeuverType.ROUNDABOUT_SLIGHT_RIGHT, ManeuverType.ROUNDABOUT_STRAIGHT,
        ManeuverType.ROUNDABOUT_SLIGHT_LEFT, ManeuverType.ROUNDABOUT_LEFT,
        ManeuverType.ROUNDABOUT_SHARP_LEFT, ManeuverType.ROUNDABOUT_UTURN,
        ManeuverType.ROUNDABOUT_SHARP_RIGHT_CW, ManeuverType.ROUNDABOUT_RIGHT_CW,
        ManeuverType.ROUNDABOUT_SLIGHT_RIGHT_CW, ManeuverType.ROUNDABOUT_STRAIGHT_CW,
        ManeuverType.ROUNDABOUT_SLIGHT_LEFT_CW, ManeuverType.ROUNDABOUT_LEFT_CW,
        ManeuverType.ROUNDABOUT_SHARP_LEFT_CW, ManeuverType.ROUNDABOUT_UTURN_CW -> SpeechWord.NAV_ROUNDABOUT
        ManeuverType.DESTINATION, ManeuverType.DESTINATION_LEFT, ManeuverType.DESTINATION_RIGHT -> SpeechWord.NAV_DESTINATION
        ManeuverType.MERGE, ManeuverType.UNKNOWN -> null
    }
}
