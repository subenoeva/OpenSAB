package com.opensab.app.ui.settings

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.opensab.app.ui.preview.PreviewMockData
import com.opensab.domain.music.MediaLibraryNode
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.InboundCallMode
import com.opensab.domain.settings.InboundMessageMode
import com.opensab.domain.settings.ThemeMode

class SettingsScreenPreviewProvider : PreviewParameterProvider<SettingsUiState> {
    override val values: Sequence<SettingsUiState> = sequenceOf(
        // Scenario 1: Default populated settings with paired vehicles
        SettingsUiState(
            settings = AppSettings(favoriteMediaPackage = "com.spotify.music"),
            knownVehicles = listOf(
                PreviewMockData.sampleVehicle1,
                PreviewMockData.sampleVehicle2,
                PreviewMockData.sampleVehicle3
            ),
            installedMediaApps = PreviewMockData.sampleMediaApps
        ),
        // Scenario 2: Customized settings (miles, dark theme, verbose logging)
        SettingsUiState(
            settings = AppSettings(
                autoConnect = true,
                autoDisconnect = false,
                keepScreenOnWhileRiding = true,
                showOnLockScreen = false,
                themeMode = ThemeMode.DARK,
                favoriteMediaPackage = "com.google.android.apps.youtube.music",
                autoResumeMusicOnConnect = true,
                handlebarVolumeControl = true,
                interceptGpsNotifications = true,
                inboundCallMode = InboundCallMode.ALERT,
                inboundMessageMode = InboundMessageMode.PROMPT,
                ctsClockSync = true,
                verboseLogging = true
            ),
            verboseLogging = true,
            knownVehicles = listOf(PreviewMockData.sampleVehicle1),
            installedMediaApps = PreviewMockData.sampleMediaApps
        ),
        // Scenario 3: Empty settings (no paired vehicles yet, ignored calls, direct read messages)
        SettingsUiState(
            settings = AppSettings(
                autoConnect = false,
                inboundCallMode = InboundCallMode.IGNORE,
                inboundMessageMode = InboundMessageMode.READ_DIRECTLY
            ),
            knownVehicles = emptyList(),
            installedMediaApps = PreviewMockData.sampleMediaApps
        ),
        // Scenario 4: Cold start picker open two levels deep, with one node already pinned.
        // The layout only exists while a browse is in flight, so without this the dialog can
        // only be reviewed on a device with a real player answering.
        SettingsUiState(
            settings = AppSettings(
                favoriteMediaPackage = "com.spotify.music",
                coldStartMediaId = "MY_COLLECTION_TRACKS",
                coldStartMediaLabel = "Canciones",
                coldStartMediaPackage = "com.spotify.music"
            ),
            installedMediaApps = PreviewMockData.sampleMediaApps,
            libraryPicker = MediaLibraryPickerState(
                nodes = listOf(
                    MediaLibraryNode(
                        mediaId = "MY_COLLECTION_ALBUMS",
                        title = "Álbumes",
                        subtitle = "128 guardados",
                        isPlayable = false,
                        isBrowsable = true
                    ),
                    MediaLibraryNode(
                        mediaId = "MY_COLLECTION_TRACKS",
                        title = "Canciones",
                        subtitle = null,
                        isPlayable = true,
                        isBrowsable = false
                    ),
                    MediaLibraryNode(
                        mediaId = "MY_COLLECTION_PLAYLIST",
                        title = "Playlists",
                        subtitle = "Tuyas y seguidas",
                        isPlayable = false,
                        isBrowsable = true
                    )
                ),
                stack = listOf(
                    MediaLibraryCrumb("ROOT_AUTO", ""),
                    MediaLibraryCrumb("MY_COLLECTION::android_auto_content_id", "Colección")
                )
            )
        ),
        // Scenario 5: the favourite player refused a browse connection, so the picker row is
        // disabled and says why. Reproducing this for real needs a player that denies its root.
        SettingsUiState(
            settings = AppSettings(
                favoriteMediaPackage = "com.spotify.music",
                inboundMessageMode = InboundMessageMode.IGNORE
            ),
            installedMediaApps = PreviewMockData.sampleMediaApps,
            libraryUnavailableFor = "com.spotify.music"
        )
    )
}
