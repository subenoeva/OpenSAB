package com.opensab.cluster.call

import com.opensab.cluster.LiveScreenPresets
import com.opensab.cluster.NavigationAction
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
import com.opensab.domain.call.CallInfo
import com.opensab.domain.call.CallStatus
import com.opensab.domain.call.TelecomAdapter
import com.opensab.domain.music.VolumeSource
import com.opensab.domain.speech.SpeechArg
import com.opensab.domain.speech.SpeechRequest
import com.opensab.domain.speech.SpeechToken
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ProjectionFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.transform

/** Live telephony page: BACK hangs up or rejects, SELECT is the only way to answer. */
class CallPageProvider(
    private val activeCall: StateFlow<CallInfo?>,
    private val copy: ClusterTextResolver,
    private val telecomAdapter: TelecomAdapter? = null,
    private val volumeSource: VolumeSource? = null
) : SpeakingPage {

    override val name: String = "CallPage"

    override val onResumeBehavior: ResumeBehavior = ResumeBehavior.RETAIN

    override fun render(requestId: Int): ProjectionFrame {
        val header = copy.resolve(ClusterToken.HEADER_PHONE)
        val call = activeCall.value ?: return LiveScreenPresets.callStandby(
            requestId = requestId,
            header = header,
            empty = copy.resolve(ClusterToken.EMPTY_CALL)
        )
        val nameOrNumber = call.callerName.ifEmpty { call.callerNumber.ifEmpty { header } }
        return when (call.status) {
            CallStatus.RINGING -> LiveScreenPresets.callRinging(
                requestId = requestId,
                header = header,
                callerName = nameOrNumber,
                statusText = copy.resolve(ClusterToken.CALL_RINGING)
            )
            CallStatus.ACTIVE -> LiveScreenPresets.call(
                requestId = requestId,
                header = header,
                callerName = nameOrNumber,
                statusText = copy.resolve(ClusterToken.CALL_IN_CALL)
            )
            CallStatus.DISCONNECTED -> LiveScreenPresets.call(
                requestId = requestId,
                header = header,
                callerName = nameOrNumber,
                statusText = copy.resolve(ClusterToken.CALL_ENDED)
            )
            CallStatus.IDLE -> LiveScreenPresets.call(
                requestId = requestId,
                header = header,
                callerName = nameOrNumber,
                statusText = ""
            )
        }
    }

    /**
     * Outside `RINGING`, `onInput(SELECT)` returns `None` without touching the adapter, so
     * [MirrorActionToken.ACCEPT_CALL] stays declared but disabled — it holds its slot showing that
     * right now it leads nowhere, instead of vanishing and shifting the rest under a glance.
     *
     * `BACK` keeps its effect in all three states — the branch always returns `Pop` — so it is
     * always enabled; only its label moves, following the state the frame paints.
     */
    override fun mirror(): MirrorPage {
        val status = activeCall.value?.status
        val backToken = if (status == CallStatus.RINGING) {
            MirrorActionToken.REJECT_CALL
        } else {
            MirrorActionToken.HANG_UP
        }
        return mirrorPageOf(
            name,
            render(MIRROR_REQUEST_ID),
            actions = listOf(
                MirrorAction(
                    token = backToken,
                    functionId = FunctionId.BACK,
                    slot = MirrorSlot.LEFT
                ),
                MirrorAction(
                    token = MirrorActionToken.ACCEPT_CALL,
                    functionId = FunctionId.SELECT,
                    slot = MirrorSlot.PRIMARY,
                    enabled = status == CallStatus.RINGING
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
    }

    private var announcedCall: CallInfo? = null

    /**
     * Announces an incoming call when it starts ringing, deduplicating repetitions while it rings
     * and resetting when the ringing stops.
     */
    override val speech: Flow<List<SpeechRequest>> = activeCall
        .transform { call ->
            if (call?.status == CallStatus.RINGING) {
                if (call != announcedCall) {
                    announcedCall = call
                    val callerName = call.callerName.trim()
                    val callerNumber = call.callerNumber.trim()
                    val digitsOnly = callerNumber.filter { it.isDigit() }
                    val request = when {
                        callerName.isNotEmpty() -> SpeechRequest(SpeechToken.CALL_RINGING, SpeechArg.Text(callerName))
                        digitsOnly.isNotEmpty() -> SpeechRequest(SpeechToken.CALL_RINGING, SpeechArg.Digits(callerNumber))
                        else -> SpeechRequest(SpeechToken.CALL_RINGING_UNKNOWN)
                    }
                    emit(listOf(request))
                }
            } else {
                announcedCall = null
            }
        }

    override val invalidations: Flow<Unit> = redrawOn(activeCall)

    override fun onInput(functionId: FunctionId): NavigationAction = when (functionId) {
        FunctionId.BACK -> {
            val call = activeCall.value
            if (call != null && call.status == CallStatus.ACTIVE) {
                telecomAdapter?.endCall()
            } else if (call != null && call.status == CallStatus.RINGING) {
                telecomAdapter?.rejectCall()
            }
            NavigationAction.Pop
        }

        FunctionId.MENU -> NavigationAction.Reset

        FunctionId.SELECT -> {
            val call = activeCall.value
            if (call != null && call.status == CallStatus.RINGING) {
                telecomAdapter?.acceptCall()
            }
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
}
