package com.opensab.app.ui.settings

import androidx.lifecycle.viewModelScope
import com.opensab.app.R
import com.opensab.app.logging.DebuggableBuild
import com.opensab.app.logging.JournalUsageSource
import com.opensab.app.source.music.FavoriteMediaRepository
import com.opensab.app.source.music.MediaAppScanner
import com.opensab.app.ui.base.BaseViewModel
import com.opensab.app.ui.permissions.PermissionEntry
import com.opensab.app.ui.permissions.PermissionStatus
import com.opensab.app.ui.permissions.PermissionStatusReader
import com.opensab.domain.music.MediaControllerAdapter
import com.opensab.domain.music.MediaLibraryBrowser
import com.opensab.domain.settings.SessionLogLimit
import com.opensab.domain.settings.SettingsRepository
import com.opensab.domain.settings.resolveVerboseLogging
import com.opensab.sab.session.crypto.SecureKeyStore
import com.opensab.sab.session.vehicle.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the settings screen: user preferences, paired motorcycle management and key material reset.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vehicleRepository: VehicleRepository,
    private val favoriteMediaRepository: FavoriteMediaRepository,
    private val keyStore: SecureKeyStore,
    private val mediaAppScanner: MediaAppScanner,
    private val permissionEntries: List<PermissionEntry>,
    private val permissionStatusReader: PermissionStatusReader,
    private val journalUsage: JournalUsageSource,
    @DebuggableBuild private val isDebuggable: Boolean,
    private val mediaControllerAdapter: MediaControllerAdapter? = null,
    private val libraryBrowser: MediaLibraryBrowser? = null
) : BaseViewModel<SettingsUiState, SettingsUiIntent, SettingsUiEvent>(SettingsUiState()) {

    init {
        // Nothing here drives navigation, so both streams idle while the screen is not observed.
        viewModelScope.launch {
            settingsRepository.settingsFlow.whileSubscribed().collect { appSettings ->
                updateState {
                    copy(
                        settings = appSettings,
                        verboseLogging = resolveVerboseLogging(appSettings.verboseLogging, isDebuggable)
                    )
                }
            }
        }

        viewModelScope.launch {
            vehicleRepository.vehiclesFlow.whileSubscribed().collect { vehicles ->
                updateState { copy(knownVehicles = vehicles) }
            }
        }

        // A release build never runs the journal, so `journal` stays null and the section hides.
        if (isDebuggable) {
            viewModelScope.launch {
                combine(journalUsage.usage, settingsRepository.settingsFlow) { usage, appSettings ->
                    // The journal's own limit can still read unbounded (no retain ran yet), so the
                    // stored setting -- not `usage.overLimit` -- is what the rider judges the bar against.
                    val limitMb = SessionLogLimit.normalize(appSettings.sessionLogLimitMb)
                    JournalSettingsUi(
                        usedBytes = usage.usedBytes,
                        limitMb = limitMb,
                        overLimit = usage.usedBytes > SessionLogLimit.bytes(limitMb)
                    )
                }.whileSubscribed().collect { journal ->
                    updateState { copy(journal = journal) }
                }
            }
        }

        refreshInstalledMediaApps()
        refreshPermissions()
    }

    override suspend fun handleIntent(intent: SettingsUiIntent) {
        when (intent) {
            is SettingsUiIntent.UpdateSettings -> {
                settingsRepository.updateSettings(intent.transform)
            }
            is SettingsUiIntent.SetFavoriteMediaPackage -> {
                setFavoriteMediaPackage(intent.packageName)
            }
            is SettingsUiIntent.ForgetVehicle -> {
                forgetVehicle(intent.macAddress)
            }
            is SettingsUiIntent.SaveVehicleProfile -> {
                saveVehicleProfile(intent.macAddress, intent.draft)
            }
            is SettingsUiIntent.ResetSessionKeys -> {
                Timber.w("Clearing all cryptographic session keys from Keystore")
                keyStore.clear()
                sendEvent(SettingsUiEvent.ShowToast(R.string.settings_toast_session_keys_reset))
            }
            is SettingsUiIntent.ResetSettingsToDefaults -> {
                Timber.i("Resetting settings to default values")
                settingsRepository.resetToDefaults()
                sendEvent(SettingsUiEvent.ShowToast(R.string.settings_toast_settings_reset))
            }
            is SettingsUiIntent.RefreshInstalledMediaApps -> {
                refreshInstalledMediaApps()
            }
            is SettingsUiIntent.StartColdStartCountdown -> {
                startColdStartCountdown()
            }
            is SettingsUiIntent.OpenColdStartPicker -> openColdStartPicker()
            is SettingsUiIntent.BrowseColdStartNode -> browseColdStartNode(intent.mediaId, intent.title)
            is SettingsUiIntent.ColdStartPickerBack -> coldStartPickerBack()
            is SettingsUiIntent.PinColdStartMedia -> pinColdStartMedia(intent.mediaId, intent.label)
            is SettingsUiIntent.CloseColdStartPicker -> closeColdStartPicker()
            is SettingsUiIntent.RefreshPermissions -> {
                refreshPermissions()
            }
            is SettingsUiIntent.OpenPermissions -> {
                sendEvent(SettingsUiEvent.NavigateToPermissions)
            }
            is SettingsUiIntent.OpenRootMenu -> {
                sendEvent(SettingsUiEvent.NavigateToRootMenu)
            }
            is SettingsUiIntent.NavigateBack -> {
                sendEvent(SettingsUiEvent.NavigateBack)
            }
            is SettingsUiIntent.SetSessionLogLimit -> {
                settingsRepository.updateSettings { it.copy(sessionLogLimitMb = intent.mb) }
            }
            is SettingsUiIntent.OpenSessions -> {
                sendEvent(SettingsUiEvent.NavigateToSessions)
            }
        }
    }

    private var countdownJob: Job? = null

    private fun startColdStartCountdown() {
        countdownJob?.cancel()
        mediaControllerAdapter?.start(viewModelScope)
        countdownJob = viewModelScope.launch {
            for (i in 5 downTo 1) {
                updateState { copy(coldStartCountdown = i) }
                delay(1.seconds)
            }
            updateState { copy(coldStartCountdown = null) }
            mediaControllerAdapter?.play()
        }
    }

    /**
     * `statusOf` does not suspend, so this runs from `init` too and the row is right in the first
     * state the screen ever sees.
     */
    private fun refreshPermissions() {
        val statuses = permissionEntries.map(permissionStatusReader::statusOf)
        val grantable = statuses.filter { it != PermissionStatus.UNSUPPORTED }

        updateState {
            copy(
                permissions = PermissionSummary(
                    granted = grantable.count { it == PermissionStatus.GRANTED },
                    total = grantable.size
                )
            )
        }
    }

    /**
     * The chosen player lives in two places on purpose: [FavoriteMediaRepository] is what the media
     * adapter reads when it has to wake a player on connect, while `AppSettings` is what the screen
     * renders. Writing only one of them makes the setting look applied while the motorcycle keeps
     * launching the previous app.
     */
    private suspend fun setFavoriteMediaPackage(packageName: String?) {
        favoriteMediaRepository.setFavoritePackage(packageName)
        settingsRepository.updateSettings { it.copy(favoriteMediaPackage = packageName) }
        // A refusal belongs to the player that gave it, so the new favourite starts unjudged.
        updateState { copy(libraryUnavailableFor = null) }
    }

    /**
     * Forgetting a motorcycle also drops its AES session key: leaving the key behind would let a
     * later pairing reuse credentials the rider believed were gone.
     */
    private suspend fun forgetVehicle(macAddress: String) {
        Timber.i("Removing vehicle profile for $macAddress")
        vehicleRepository.removeVehicle(macAddress)
        keyStore.removeSessionKey(macAddress)
        sendEvent(SettingsUiEvent.ShowToast(R.string.settings_toast_vehicle_forgotten))
    }

    /**
     * Writes only when [macAddress] still has an identity record: [VehicleRepository.updateProfile]
     * is a no-op otherwise, which is what stops a profile edit racing `ForgetVehicle` from
     * resurrecting a motorcycle the rider just removed.
     */
    private suspend fun saveVehicleProfile(macAddress: String, draft: VehicleProfileDraft) {
        val profile = draft.toProfile(seriesOf(macAddress))
        if (vehicleRepository.updateProfile(macAddress, profile)) {
            sendEvent(SettingsUiEvent.ShowToast(R.string.settings_toast_vehicle_saved))
        }
    }

    /** The series code behind [macAddress], read from what is already loaded before asking storage. */
    private suspend fun seriesOf(macAddress: String): String =
        currentState.knownVehicles.firstOrNull { it.macAddress == macAddress }?.vehicleInfo?.seriesCode
            ?: vehicleRepository.getVehicle(macAddress)?.vehicleInfo?.seriesCode
            ?: ""

    private suspend fun openColdStartPicker() {
        val browser = libraryBrowser ?: return
        val target = currentState.settings.favoriteMediaPackage ?: return

        updateState { copy(libraryPicker = MediaLibraryPickerState(isLoading = true)) }
        val rootId = browser.connect(target)
        if (rootId == null) {
            Timber.w("$target refused a browse connection; marking its library as unavailable")
            updateState { copy(libraryPicker = null, libraryUnavailableFor = target) }
            sendEvent(SettingsUiEvent.ShowToast(R.string.settings_cold_start_picker_failed))
            return
        }

        val nodes = browser.browse(rootId)
        updateState {
            copy(
                libraryPicker = MediaLibraryPickerState(
                    isLoading = false,
                    nodes = nodes,
                    stack = listOf(MediaLibraryCrumb(rootId, ""))
                )
            )
        }
    }

    private suspend fun browseColdStartNode(mediaId: String, title: String) {
        val browser = libraryBrowser ?: return
        val picker = currentState.libraryPicker ?: return

        updateState { copy(libraryPicker = picker.copy(isLoading = true)) }
        val nodes = browser.browse(mediaId)
        updateState {
            copy(
                libraryPicker = picker.copy(
                    isLoading = false,
                    nodes = nodes,
                    stack = picker.stack + MediaLibraryCrumb(mediaId, title)
                )
            )
        }
    }

    private suspend fun coldStartPickerBack() {
        val browser = libraryBrowser ?: return
        val picker = currentState.libraryPicker ?: return
        // One crumb left means we are at the root, and back out of the root closes the picker.
        if (picker.stack.size <= 1) {
            browser.disconnect()
            updateState { copy(libraryPicker = null) }
            return
        }

        val trimmed = picker.stack.dropLast(1)
        updateState { copy(libraryPicker = picker.copy(isLoading = true)) }
        val nodes = browser.browse(trimmed.last().mediaId)
        updateState {
            copy(libraryPicker = picker.copy(isLoading = false, nodes = nodes, stack = trimmed))
        }
    }

    private suspend fun pinColdStartMedia(mediaId: String, label: String) {
        val target = currentState.settings.favoriteMediaPackage
        settingsRepository.updateSettings {
            it.copy(
                coldStartMediaId = mediaId,
                coldStartMediaLabel = label,
                coldStartMediaPackage = target
            )
        }
        closeColdStartPicker()
    }

    private fun closeColdStartPicker() {
        libraryBrowser?.disconnect()
        updateState { copy(libraryPicker = null) }
    }

    private fun refreshInstalledMediaApps() {
        val apps = mediaAppScanner.getInstalledMediaApps()
        updateState { copy(installedMediaApps = apps) }
    }
}
