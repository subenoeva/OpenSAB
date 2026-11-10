package com.opensab.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensab.app.BuildConfig
import com.opensab.app.R
import com.opensab.app.source.music.exposesBrowseTree
import com.opensab.domain.music.MediaLibraryNode
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.MuteAction

@Composable
internal fun MusicSection(
    state: SettingsUiState,
    onIntent: (SettingsUiIntent) -> Unit,
    onPickMusicApp: () -> Unit
) {
    val settings = state.settings
    SettingsCategory(
        title = stringResource(R.string.settings_category_music),
        icon = Icons.Default.MusicNote
    ) {
        val currentMusicAppName = state.installedMediaApps.appNameFor(settings.favoriteMediaPackage)
            ?: stringResource(R.string.media_player_system_default)

        SettingsValueSelectorItem(
            title = stringResource(R.string.settings_fav_music_player_title),
            value = currentMusicAppName,
            subtitle = stringResource(R.string.settings_fav_music_player_desc),
            onClick = onPickMusicApp
        )
        // Without a MediaBrowserService there is no tree to walk. Being in the player
        // list is not enough: the scanner also lists apps found by their launcher
        // activity or by name alone.
        if (state.installedMediaApps.exposesBrowseTree(settings.favoriteMediaPackage)) {
            // Declaring a MediaBrowserService is not handing out its tree: Spotify
            // answers the bind and then refuses the root. That only shows up on
            // connect, so the row explains itself once refused instead of vanishing.
            val refused = state.isLibraryRefused()
            SettingsValueSelectorItem(
                title = stringResource(R.string.settings_cold_start_content_title),
                value = settings.pinnedLabelForFavourite()
                    ?: stringResource(R.string.settings_cold_start_content_none),
                subtitle = if (refused) {
                    stringResource(R.string.settings_cold_start_content_blocked, currentMusicAppName)
                } else {
                    stringResource(R.string.settings_cold_start_content_desc)
                },
                enabled = !refused,
                onClick = { onIntent(SettingsUiIntent.OpenColdStartPicker) }
            )
        }
        SettingsSwitchItem(
            title = stringResource(R.string.settings_auto_resume_title),
            subtitle = stringResource(R.string.settings_auto_resume_desc),
            checked = settings.autoResumeMusicOnConnect,
            onCheckedChange = { checked ->
                onIntent(SettingsUiIntent.UpdateSettings {
                    it.copy(
                        autoResumeMusicOnConnect = checked
                    )
                })
            }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.settings_handlebar_volume_title),
            subtitle = stringResource(R.string.settings_handlebar_volume_desc),
            checked = settings.handlebarVolumeControl,
            onCheckedChange = { checked ->
                onIntent(SettingsUiIntent.UpdateSettings {
                    it.copy(
                        handlebarVolumeControl = checked
                    )
                })
            }
        )
        SettingsSegmentedTabSelector(
            title = stringResource(R.string.settings_mute_action_title),
            subtitle = stringResource(R.string.settings_mute_action_desc),
            options = MuteAction.entries,
            selected = settings.muteAction,
            optionLabel = { action ->
                when (action) {
                    MuteAction.PAUSE_MUSIC -> stringResource(R.string.settings_mute_action_pause_label)
                    MuteAction.MUTE_ALL -> stringResource(R.string.settings_mute_action_all_label)
                }
            },
            optionIcon = { action ->
                when (action) {
                    MuteAction.PAUSE_MUSIC -> Icons.Default.Pause
                    MuteAction.MUTE_ALL -> Icons.AutoMirrored.Filled.VolumeOff
                }
            },
            onSelect = { mode ->
                onIntent(SettingsUiIntent.UpdateSettings { it.copy(muteAction = mode) })
            }
        )
        if (BuildConfig.DEBUG) {
            SettingsActionItem(
                title = stringResource(R.string.settings_cold_start_test_title),
                subtitle = if (state.coldStartCountdown != null) {
                    stringResource(R.string.settings_cold_start_countdown_running, state.coldStartCountdown)
                } else {
                    stringResource(R.string.settings_cold_start_test_desc)
                },
                icon = Icons.Default.PlayArrow,
                onClick = { onIntent(SettingsUiIntent.StartColdStartCountdown) }
            )
        }
    }
}

@Composable
internal fun ColdStartPickerDialog(
    picker: MediaLibraryPickerState,
    settings: AppSettings,
    onIntent: (SettingsUiIntent) -> Unit
) {
    val pinnedMediaId = settings.pinnedMediaIdForFavourite()
    val rootLabel = stringResource(R.string.settings_cold_start_picker_root)

    AlertDialog(
        onDismissRequest = { onIntent(SettingsUiIntent.CloseColdStartPicker) },
        title = { MediaLibraryBreadcrumb(stack = picker.stack, rootLabel = rootLabel) },
        text = {
            when {
                picker.isLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                picker.nodes.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_cold_start_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(
                        items = picker.nodes,
                        key = { index, node -> pickerRowKey(index, node) }
                    ) { _, node ->
                        MediaLibraryRow(
                            node = node,
                            isPinned = node.mediaId == pinnedMediaId,
                            onClick = { onIntent(node.pickIntent()) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            picker.stack.pinHereTarget()?.let { current ->
                TextButton(onClick = {
                    onIntent(SettingsUiIntent.PinColdStartMedia(current.mediaId, current.title))
                }) { Text(stringResource(R.string.settings_cold_start_pin_here)) }
            }
        },
        dismissButton = {
            // "Back", not "Cancel": this walks one level up and only closes at the root.
            TextButton(onClick = { onIntent(SettingsUiIntent.ColdStartPickerBack) }) {
                Text(stringResource(R.string.settings_cold_start_picker_back))
            }
        }
    )
}

/**
 * Text, not buttons: the ViewModel walks one level at a time, so jumping to an arbitrary crumb
 * would need an intent of its own.
 */
@Composable
private fun MediaLibraryBreadcrumb(
    stack: List<MediaLibraryCrumb>,
    rootLabel: String,
    modifier: Modifier = Modifier
) {
    val titles = breadcrumbTitles(stack, rootLabel)

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        titles.forEachIndexed { index, title ->
            if (index > 0) {
                Text(
                    text = " › ",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val isCurrent = index == titles.lastIndex
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun MediaLibraryRow(
    node: MediaLibraryNode,
    isPinned: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        // The dialog paints its own surface; an opaque list item would tile a second one on top.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = node.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isPinned) FontWeight.Bold else FontWeight.Normal,
                color = if (isPinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        },
        supportingContent = {
            val support = if (isPinned) {
                stringResource(R.string.settings_cold_start_picker_pinned)
            } else {
                node.subtitle
            }
            support?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isPinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (isPinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (node.isBrowsable) {
                        Icons.Default.Folder
                    } else {
                        Icons.Default.MusicNote
                    },
                    contentDescription = null,
                    tint = if (isPinned) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        trailingContent = {
            if (node.isBrowsable) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isPinned) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}
