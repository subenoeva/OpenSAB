package com.opensab.app.ui.settings

import androidx.annotation.StringRes
import com.opensab.domain.music.MediaAppInfo
import com.opensab.domain.music.MediaLibraryNode
import com.opensab.domain.settings.AppSettings
import com.opensab.sab.session.vehicle.KnownVehicle

/** Both numbers exclude the `UNSUPPORTED` rows: this phone cannot grant them at all. */
data class PermissionSummary(val granted: Int = 0, val total: Int = 0)

/** One level of the browse path, kept so the picker can draw a breadcrumb and walk back. */
data class MediaLibraryCrumb(val mediaId: String, val title: String)

/**
 * The picker's own state. Null in [SettingsUiState] means closed, which is also what keeps the
 * browser disconnected: there is no open picker without a live connection.
 */
data class MediaLibraryPickerState(
    val isLoading: Boolean = false,
    val nodes: List<MediaLibraryNode> = emptyList(),
    val stack: List<MediaLibraryCrumb> = emptyList()
)

/** Null hides the whole «Session log» section: a release build records nothing to show. */
data class JournalSettingsUi(val usedBytes: Long, val limitMb: Int, val overLimit: Boolean)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),

    /** `settings.verboseLogging` resolved against the build: what the switch shows. */
    val verboseLogging: Boolean = false,
    val knownVehicles: List<KnownVehicle> = emptyList(),
    val installedMediaApps: List<MediaAppInfo> = emptyList(),
    val permissions: PermissionSummary = PermissionSummary(),
    val isLoading: Boolean = false,
    val coldStartCountdown: Int? = null,
    val libraryPicker: MediaLibraryPickerState? = null,
    val journal: JournalSettingsUi? = null,

    /**
     * The player that refused a browse connection, if one did. Learned from the refusal rather than
     * a hardcoded list, because whether an app hands out its tree can change with any release
     * (Spotify answers the bind and then returns no root). Not persisted: a refusal can also be a
     * player whose session died.
     */
    val libraryUnavailableFor: String? = null
)

sealed interface SettingsUiIntent {
    data class UpdateSettings(val transform: (AppSettings) -> AppSettings) : SettingsUiIntent
    data class SetFavoriteMediaPackage(val packageName: String?) : SettingsUiIntent
    data class ForgetVehicle(val macAddress: String) : SettingsUiIntent
    data class SaveVehicleProfile(val macAddress: String, val draft: VehicleProfileDraft) : SettingsUiIntent
    data object ResetSessionKeys : SettingsUiIntent
    data object ResetSettingsToDefaults : SettingsUiIntent
    data object RefreshInstalledMediaApps : SettingsUiIntent
    data object StartColdStartCountdown : SettingsUiIntent

    data object OpenColdStartPicker : SettingsUiIntent
    data class BrowseColdStartNode(val mediaId: String, val title: String) : SettingsUiIntent
    data object ColdStartPickerBack : SettingsUiIntent
    data class PinColdStartMedia(val mediaId: String, val label: String) : SettingsUiIntent
    data object CloseColdStartPicker : SettingsUiIntent

    /** Sent on ON_RESUME. */
    data object RefreshPermissions : SettingsUiIntent
    data object OpenPermissions : SettingsUiIntent
    data object OpenRootMenu : SettingsUiIntent
    data object NavigateBack : SettingsUiIntent

    data class SetSessionLogLimit(val mb: Int) : SettingsUiIntent
    data object OpenSessions : SettingsUiIntent
}

sealed interface SettingsUiEvent {
    data class ShowToast(@StringRes val messageResId: Int) : SettingsUiEvent
    data object NavigateToPermissions : SettingsUiEvent
    data object NavigateToRootMenu : SettingsUiEvent
    data object NavigateBack : SettingsUiEvent
    data object NavigateToSessions : SettingsUiEvent
}
