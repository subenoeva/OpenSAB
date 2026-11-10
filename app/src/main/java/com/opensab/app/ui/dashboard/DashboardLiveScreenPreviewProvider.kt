package com.opensab.app.ui.dashboard

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.opensab.app.ui.preview.PreviewMockData
import com.opensab.cluster.mirror.MirrorAction
import com.opensab.cluster.mirror.MirrorActionToken
import com.opensab.cluster.mirror.MirrorDelivery
import com.opensab.cluster.mirror.MirrorPage
import com.opensab.cluster.mirror.MirrorPopUp
import com.opensab.cluster.mirror.MirrorSlot
import com.opensab.cluster.mirror.MirrorState
import com.opensab.domain.music.MusicState
import com.opensab.domain.music.PlaybackStatus
import com.opensab.domain.music.TrackMetadata
import com.opensab.sab.protocol.assignment.ColorId
import com.opensab.sab.protocol.assignment.FunctionId
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.session.ConnectionStep
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.projection.PopUpPriority

class DashboardLiveScreenPreviewProvider : PreviewParameterProvider<DashboardUiState> {
    override val values: Sequence<DashboardUiState> = sequenceOf(
        // Scenario 1: Connected to Forza 750 with active music playback
        DashboardUiState(
            sessionState = SabSessionState.Connected(
                vehicle = PreviewMockData.sampleVehicle1,
                session = PreviewMockData.sampleSession1
            ),
            activeVehicle = PreviewMockData.sampleVehicle1,
            musicState = MusicState(
                status = PlaybackStatus.PLAYING,
                metadata = TrackMetadata(
                    title = "Highway to Hell",
                    artist = "AC/DC",
                    album = "Highway to Hell"
                )
            )
        ),
        // Scenario 2: Connected to X-ADV with audio paused / idle
        DashboardUiState(
            sessionState = SabSessionState.Connected(
                vehicle = PreviewMockData.sampleVehicle2,
                session = PreviewMockData.sampleSession2
            ),
            activeVehicle = PreviewMockData.sampleVehicle2,
            musicState = MusicState(
                status = PlaybackStatus.PAUSED,
                metadata = TrackMetadata()
            )
        ),
        // Scenario 3: Connected to ADV 350 LCD monochrome
        DashboardUiState(
            sessionState = SabSessionState.Connected(
                vehicle = PreviewMockData.sampleVehicle3,
                session = PreviewMockData.sampleSession3
            ),
            activeVehicle = PreviewMockData.sampleVehicle3,
            musicState = MusicState(
                status = PlaybackStatus.PLAYING,
                metadata = TrackMetadata(
                    title = "Riders on the Storm",
                    artist = "The Doors",
                    album = "L.A. Woman"
                )
            )
        ),
        // Scenario 4: The link went down and the pipeline is rebuilding it. Reachable whenever the
        // rider returns to a backgrounded app, where the NavigateToScan event has not landed yet.
        DashboardUiState(
            sessionState = SabSessionState.Connecting(ConnectionStep.ESTABLISHING_SECURITY),
            activeVehicle = PreviewMockData.sampleVehicle1,
            musicState = MusicState(status = PlaybackStatus.PAUSED)
        ),
        // Scenario 5: the native tachometer owns the screen, and the handover is out but unanswered
        connected(MirrorState.ClusterOwnsScreen(handoverPending = true)),
        // Scenario 6: mirroring the music page, confirmed on the cluster
        connected(
            MirrorState.Projecting(
                page = musicPage,
                trail = listOf("RootMenu", "MusicPage"),
                indicators = setOf(ActiveIndicator.MUSIC),
                delivery = MirrorDelivery.Confirmed
            )
        ),
        // Scenario 7: an incoming call modal, with the page zones inert underneath
        connected(
            MirrorState.Projecting(
                page = musicPage,
                popUp = MirrorPopUp(
                    id = "incoming-call",
                    message = "Alice",
                    priority = PopUpPriority.INCOMING_CALL,
                    actions = listOf(
                        MirrorAction(MirrorActionToken.DISMISS, FunctionId.BACK, MirrorSlot.LEFT),
                        MirrorAction(MirrorActionToken.DISMISS, FunctionId.SELECT, MirrorSlot.RIGHT)
                    )
                ),
                trail = listOf("RootMenu", "MusicPage"),
                indicators = setOf(ActiveIndicator.MUSIC, ActiveIndicator.PHONE),
                delivery = MirrorDelivery.Confirmed
            )
        ),
        // Scenario 8: the cluster refused the frame, so the mirror is painted and stale
        connected(
            MirrorState.Projecting(
                page = musicPage,
                trail = listOf("RootMenu", "MusicPage"),
                delivery = MirrorDelivery.Rejected(status = 2, errorPosition = 7)
            )
        )
    )

    private companion object {

        /** The music page as the cluster would have received it, labels and accent included. */
        val musicPage = MirrorPage(
            pageName = "MusicPage",
            title = "Music",
            lines = listOf("Highway to Hell", "AC/DC"),
            accent = ColorId.ORANGE,
            actions = listOf(
                MirrorAction(MirrorActionToken.PAUSE, FunctionId.BACK, MirrorSlot.PRIMARY),
                MirrorAction(MirrorActionToken.NEXT_TRACK, FunctionId.SELECT, MirrorSlot.RIGHT),
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
                    MirrorActionToken.VOLUME_MUTE,
                    FunctionId.VOLUME_MUTE,
                    MirrorSlot.LEFT
                ),
                MirrorAction(MirrorActionToken.OPEN_ROOT_MENU, FunctionId.MENU, MirrorSlot.HEADER)
            )
        )

        fun connected(mirror: MirrorState) = DashboardUiState(
            sessionState = SabSessionState.Connected(
                vehicle = PreviewMockData.sampleVehicle1,
                session = PreviewMockData.sampleSession1
            ),
            activeVehicle = PreviewMockData.sampleVehicle1,
            musicState = MusicState(
                status = PlaybackStatus.PLAYING,
                metadata = TrackMetadata(title = "Highway to Hell", artist = "AC/DC")
            ),
            mirrorState = mirror
        )
    }
}
