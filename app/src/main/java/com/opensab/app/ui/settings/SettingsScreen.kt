package com.opensab.app.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.opensab.app.R
import com.opensab.app.theme.OpenSABTheme
import com.opensab.domain.settings.TemperatureUnit
import com.opensab.domain.settings.ThemeMode
import com.opensab.domain.settings.Tunable
import com.opensab.domain.settings.resolveQuickReplies
import com.opensab.domain.settings.resolveTemperatureUnit
import com.opensab.sab.session.vehicle.KnownVehicle


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onIntent: (SettingsUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = state.settings
    var vehicleToDelete by remember { mutableStateOf<KnownVehicle?>(null) }
    var vehicleToEdit by remember { mutableStateOf<KnownVehicle?>(null) }
    var showResetKeysDialog by remember { mutableStateOf(false) }
    var showResetDefaultsDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showTemperatureDialog by remember { mutableStateOf(false) }
    var showMusicAppDialog by remember { mutableStateOf(false) }
    var showQuickRepliesDialog by remember { mutableStateOf(false) }
    var tunableBeingEdited by remember { mutableStateOf<Tunable?>(null) }

    // Several permissions can only change outside the app, so the count is re-read on the way
    // back to the foreground rather than observed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onIntent(SettingsUiIntent.RefreshPermissions)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.settings_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onIntent(SettingsUiIntent.NavigateBack) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        // safeDrawing rather than the default systemBars, so a display cutout in landscape is
        // counted too and the rows do not run under the notch.
        contentWindowInsets = WindowInsets.safeDrawing
    ) { paddingValues ->
        val layoutDirection = LocalLayoutDirection.current

        // The insets do not go on the modifier: padding the list would clip its viewport and the
        // cards would stop dead at the navigation bar. They go on contentPadding, so the first and
        // last card rest clear of the bars and everything in between scrolls under them.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues)
                .padding(
                    start = paddingValues.calculateStartPadding(layoutDirection) + 16.dp,
                    end = paddingValues.calculateEndPadding(layoutDirection) + 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 12.dp,
                bottom = paddingValues.calculateBottomPadding() + 32.dp
            )
        ) {
            // Section: Permissions. First because it explains why anything else is not working.
            item {
                PermissionsSection(permissions = state.permissions, onIntent = onIntent)
            }

            // Section: Connectivity & Auto-connect
            item {
                ConnectivitySection(
                    settings = settings,
                    onIntent = onIntent,
                    onResetKeys = { showResetKeysDialog = true },
                    onEditTunable = { tunableBeingEdited = it }
                )
            }

            // Section: Paired Motorcycles
            item {
                PairedVehiclesSection(
                    knownVehicles = state.knownVehicles,
                    onEdit = { vehicleToEdit = it },
                    onDelete = { vehicleToDelete = it }
                )
            }

            // Section: Display & Cluster
            item {
                DisplaySection(settings = settings, onIntent = onIntent, onPickTheme = { showThemeDialog = true })
            }

            // Section: Music & Audio
            item {
                MusicSection(state = state, onIntent = onIntent, onPickMusicApp = { showMusicAppDialog = true })
            }

            // Section: Navigation & GPS
            item {
                NavigationSection(settings = settings, onIntent = onIntent)
            }

            // Section: Weather
            item {
                WeatherSection(
                    settings = settings,
                    onPickTemperatureUnit = { showTemperatureDialog = true },
                    onEditTunable = { tunableBeingEdited = it }
                )
            }

            // Section: Messages
            item {
                MessagesSection(
                    settings = settings,
                    onIntent = onIntent,
                    onEditQuickReplies = { showQuickRepliesDialog = true },
                    onEditTunable = { tunableBeingEdited = it }
                )
            }

            // Section: Calls & Telephony
            item {
                CallsSection(settings = settings, onIntent = onIntent)
            }

            // Section: Sound Cues
            item {
                SoundCuesSection(settings = settings, onIntent = onIntent)
            }

            // Section: Voice Guidance
            item {
                VoiceSection(settings = settings, onIntent = onIntent)
            }

            // Section: Time Synchronization
            item {
                TimeSection(settings = settings, onIntent = onIntent)
            }

            // Section: Session log. Absent on a release build: there is nothing recorded to show.
            state.journal?.let { journal ->
                item {
                    JournalSection(journal = journal, onIntent = onIntent)
                }
            }

            // Section: Diagnostics & About
            item {
                AboutSection(settings = settings, verboseLogging = state.verboseLogging, onIntent = onIntent, onResetDefaults = { showResetDefaultsDialog = true })
            }
        }
    }

    vehicleToEdit?.let { vehicle ->
        VehicleProfileDialog(
            vehicle = vehicle,
            onSave = { draft ->
                onIntent(SettingsUiIntent.SaveVehicleProfile(vehicle.macAddress, draft))
                vehicleToEdit = null
            },
            onDismiss = { vehicleToEdit = null }
        )
    }

    // Dialog: Delete Paired Motorcycle
    vehicleToDelete?.let { vehicle ->
        ForgetVehicleDialog(vehicle = vehicle, onIntent = onIntent, onDismiss = { vehicleToDelete = null })
    }

    // Dialog: Reset Session Keys
    if (showResetKeysDialog) {
        ResetSessionKeysDialog(onIntent = onIntent, onDismiss = { showResetKeysDialog = false })
    }

    // Dialog: Reset Defaults
    if (showResetDefaultsDialog) {
        ResetDefaultsDialog(onIntent = onIntent, onDismiss = { showResetDefaultsDialog = false })
    }

    // Dialog: Temperature Unit Selector
    if (showTemperatureDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_temperature_unit_title),
            options = TemperatureUnit.entries,
            selected = resolveTemperatureUnit(
                settings.temperatureUnit,
                LocalLocale.current.platformLocale.country
            ),
            optionLabel = { "${it.localizedLabel()} (${it.symbol})" },
            onSelect = {
                onIntent(SettingsUiIntent.UpdateSettings { s -> s.copy(temperatureUnit = it) })
                showTemperatureDialog = false
            },
            onDismiss = { showTemperatureDialog = false }
        )
    }

    // Dialog: Quick Replies
    if (showQuickRepliesDialog) {
        val defaults = stringArrayResource(R.array.default_quick_replies).toList()
        QuickRepliesDialog(
            // Seeded with what the cluster is offering today, whether that is his or the app's:
            // the rider edits what he has seen, not an empty form.
            initial = resolveQuickReplies(settings.quickReplies, defaults),
            onSave = { edited ->
                onIntent(SettingsUiIntent.UpdateSettings { it.copy(quickReplies = edited) })
                showQuickRepliesDialog = false
            },
            onDismiss = { showQuickRepliesDialog = false }
        )
    }

    // Dialog: a wait or an interval the rider may move
    tunableBeingEdited?.let { tunable ->
        TunableDialog(
            tunable = tunable,
            initial = tunable.valueIn(settings),
            onSave = { value ->
                onIntent(SettingsUiIntent.UpdateSettings { tunable.write(it, value) })
                tunableBeingEdited = null
            },
            onDismiss = { tunableBeingEdited = null }
        )
    }

    // Dialog: Theme Selector
    if (showThemeDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_theme_title),
            options = ThemeMode.entries,
            selected = settings.themeMode,
            optionLabel = { it.localizedLabel() },
            onSelect = {
                onIntent(SettingsUiIntent.UpdateSettings { s -> s.copy(themeMode = it) })
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    // Dialog: Music App Selector
    if (showMusicAppDialog) {
        val appOptions = listOf(null) + state.installedMediaApps.map { it.packageName }
        SelectionDialog(
            title = stringResource(R.string.settings_fav_music_player_title),
            options = appOptions,
            selected = settings.favoriteMediaPackage,
            optionLabel = { pkg ->
                if (pkg == null) {
                    stringResource(R.string.media_player_system_default)
                } else {
                    state.installedMediaApps.appNameFor(pkg) ?: pkg
                }
            },
            dismissButtonLabel = stringResource(R.string.dialog_action_close),
            onSelect = { pkg ->
                onIntent(SettingsUiIntent.SetFavoriteMediaPackage(pkg))
                showMusicAppDialog = false
            },
            onDismiss = { showMusicAppDialog = false }
        )
    }

    // Dialog: Cold Start Content Picker
    state.libraryPicker?.let { picker ->
        ColdStartPickerDialog(picker = picker, settings = settings, onIntent = onIntent)
    }
}

@Preview(name = "Light Mode", showBackground = true)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true
)
@Composable
private fun SettingsScreenPreview(
    @PreviewParameter(SettingsScreenPreviewProvider::class) state: SettingsUiState
) {
    OpenSABTheme {
        SettingsScreen(
            state = state,
            onIntent = {}
        )
    }
}
