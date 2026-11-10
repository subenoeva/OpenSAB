package com.opensab.app.ui.settings.rootmenu

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.opensab.app.R
import com.opensab.app.theme.OpenSABTheme
import com.opensab.domain.settings.RootMenuEntry
import com.opensab.domain.settings.RootMenuRow
import com.opensab.domain.settings.canHide
import com.opensab.domain.settings.moved

/**
 * The drag is local while the finger is down: rows swap on screen as the dragged one crosses half
 * of its neighbour, and only the drop reaches the view model, once per gesture. The stored layout
 * replaces the local one only while no row is lifted, so a write landing mid-drag cannot yank the
 * row from under the finger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootMenuSettingsScreen(
    state: RootMenuSettingsUiState,
    onIntent: (RootMenuSettingsUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var local by remember { mutableStateOf(state.rows) }
    var dragged by remember { mutableStateOf<RootMenuEntry?>(null) }
    var dragStart by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(0f) }
    // The gesture outlives the composition that started it, so a cancel reads the rows through this.
    val storedRows by rememberUpdatedState(state.rows)

    LaunchedEffect(state.rows) {
        if (dragged == null) local = state.rows
    }

    fun endDrag() {
        dragged = null
        dragStart = -1
        offset = 0f
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_root_menu_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onIntent(RootMenuSettingsUiIntent.NavigateBack) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { onIntent(RootMenuSettingsUiIntent.Reset) }) {
                        Text(stringResource(R.string.root_menu_settings_reset))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { paddingValues ->
        val layoutDirection = LocalLayoutDirection.current

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues)
                .padding(
                    start = paddingValues.calculateStartPadding(layoutDirection) + 16.dp,
                    end = paddingValues.calculateEndPadding(layoutDirection) + 16.dp
                ),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 12.dp,
                bottom = paddingValues.calculateBottomPadding() + 32.dp
            ),
            // Rows sit edge to edge: the drag measures one row's height as the pitch between two.
            verticalArrangement = Arrangement.Top
        ) {
            item(key = "description") {
                Text(
                    text = stringResource(R.string.root_menu_settings_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            itemsIndexed(local, key = { _, row -> row.entry }) { index, row ->
                val isDragged = row.entry == dragged
                val elevation by animateDpAsState(
                    targetValue = if (isDragged) 8.dp else 0.dp,
                    label = "rowLift"
                )

                RootMenuSettingsRow(
                    row = row,
                    canToggle = !row.visible || local.canHide(row.entry),
                    onToggle = { onIntent(RootMenuSettingsUiIntent.Toggle(row.entry)) },
                    onMoveUp = if (index > 0) {
                        { onIntent(RootMenuSettingsUiIntent.Move(index, index - 1)) }
                    } else {
                        null
                    },
                    onMoveDown = if (index < local.lastIndex) {
                        { onIntent(RootMenuSettingsUiIntent.Move(index, index + 1)) }
                    } else {
                        null
                    },
                    elevation = elevation,
                    modifier = Modifier
                        .then(if (isDragged) Modifier else Modifier.animateItem())
                        .onSizeChanged { rowHeight = it.height.toFloat() }
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) offset else 0f },
                    handleModifier = Modifier.pointerInput(row.entry) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragged = row.entry
                                dragStart = local.indexOfFirst { it.entry == row.entry }
                                offset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                offset += amount.y
                                val from = local.indexOfFirst { it.entry == row.entry }
                                val to = dragTargetIndex(from, offset, rowHeight, local.size)
                                if (to != from) {
                                    local = local.moved(from, to)
                                    offset -= (to - from) * rowHeight
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onDragEnd = {
                                val end = local.indexOfFirst { it.entry == row.entry }
                                if (dragStart >= 0 && end != dragStart) {
                                    onIntent(RootMenuSettingsUiIntent.Move(dragStart, end))
                                }
                                endDrag()
                            },
                            onDragCancel = {
                                local = storedRows
                                endDrag()
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun RootMenuSettingsRow(
    row: RootMenuRow,
    canToggle: Boolean,
    onToggle: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    elevation: Dp,
    modifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier
) {
    val label = stringResource(row.entry.labelRes)
    val dragDescription = stringResource(R.string.root_menu_settings_drag, label)
    val moveUp = stringResource(R.string.root_menu_settings_move_up)
    val moveDown = stringResource(R.string.root_menu_settings_move_down)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (elevation > 0.dp) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surface
        },
        shadowElevation = elevation
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .toggleable(
                        value = row.visible,
                        enabled = canToggle,
                        role = Role.Checkbox,
                        onValueChange = { onToggle() }
                    )
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .alpha(if (row.visible) 1f else 0.4f)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = row.entry.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (row.visible) 1f else 0.5f)
                )
                // The row carries the toggle and its semantics; the box only draws it.
                Checkbox(checked = row.visible, onCheckedChange = null, enabled = canToggle)
            }
            Box(
                modifier = handleModifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = dragDescription
                        customActions = listOfNotNull(
                            onMoveUp?.let { move -> CustomAccessibilityAction(moveUp) { move(); true } },
                            onMoveDown?.let { move -> CustomAccessibilityAction(moveDown) { move(); true } }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val RootMenuEntry.icon: ImageVector
    get() = when (this) {
        RootMenuEntry.WEATHER -> Icons.Default.WbSunny
        RootMenuEntry.NAVIGATION -> Icons.Default.Navigation
        RootMenuEntry.CALL -> Icons.Default.Call
        RootMenuEntry.MESSAGE -> Icons.AutoMirrored.Filled.Message
        RootMenuEntry.MUSIC -> Icons.Default.MusicNote
        RootMenuEntry.VOLUME -> Icons.AutoMirrored.Filled.VolumeUp
    }

@get:StringRes
private val RootMenuEntry.labelRes: Int
    get() = when (this) {
        RootMenuEntry.WEATHER -> R.string.cluster_header_weather
        RootMenuEntry.NAVIGATION -> R.string.cluster_header_navigation
        RootMenuEntry.CALL -> R.string.cluster_header_phone
        RootMenuEntry.MESSAGE -> R.string.cluster_header_messages
        RootMenuEntry.MUSIC -> R.string.cluster_header_music
        RootMenuEntry.VOLUME -> R.string.cluster_header_volume
    }

@Preview(name = "Light Mode", showBackground = true)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true
)
@Preview(name = "Spanish", locale = "es", showBackground = true)
@Composable
private fun RootMenuSettingsScreenPreview(
    @PreviewParameter(RootMenuSettingsScreenPreviewProvider::class) state: RootMenuSettingsUiState
) {
    OpenSABTheme {
        RootMenuSettingsScreen(
            state = state,
            onIntent = {}
        )
    }
}
