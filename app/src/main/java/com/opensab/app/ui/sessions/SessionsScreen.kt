package com.opensab.app.ui.sessions

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.opensab.app.R
import com.opensab.app.theme.OpenSABTheme
import com.opensab.logging.journal.SessionListing
import com.opensab.logging.journal.SessionSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/** The recorded sessions, newest first, and one session's detail over them while it is open. */
@Composable
fun SessionsScreen(
    state: SessionsUiState,
    onIntent: (SessionsUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val detail = state.detail
    if (detail != null) {
        BackHandler { onIntent(SessionsUiIntent.CloseDetail) }
        SessionDetail(listing = detail, onIntent = onIntent, modifier = modifier)
    } else {
        SessionList(state = state, onIntent = onIntent, modifier = modifier)
    }

    if (state.pendingExport != null) {
        AlertDialog(
            onDismissRequest = { onIntent(SessionsUiIntent.CancelExport) },
            title = { Text(stringResource(R.string.sessions_privacy_title)) },
            text = { Text(stringResource(R.string.sessions_privacy_body)) },
            confirmButton = {
                TextButton(onClick = { onIntent(SessionsUiIntent.ConfirmExport) }) {
                    Text(stringResource(R.string.sessions_privacy_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(SessionsUiIntent.CancelExport) }) {
                    Text(stringResource(R.string.dialog_action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionList(
    state: SessionsUiState,
    onIntent: (SessionsUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val selecting = state.selected.isNotEmpty()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selecting) state.selected.size.toString() else stringResource(R.string.sessions_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onIntent(SessionsUiIntent.NavigateBack) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = { onIntent(SessionsUiIntent.ShareSelected) }) {
                            Text(stringResource(R.string.sessions_share_selected))
                        }
                    } else {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.sessions_share_all)
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessions_share_all)) },
                                    onClick = {
                                        menuOpen = false
                                        onIntent(SessionsUiIntent.ShareAll)
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { padding ->
        when {
            state.isLoading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "filter") {
                    FilterChip(
                        selected = state.onlyAttention,
                        onClick = { onIntent(SessionsUiIntent.SetOnlyAttention(!state.onlyAttention)) },
                        label = { Text(stringResource(R.string.sessions_only_attention)) }
                    )
                }
                val rows = state.rows
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.sessions_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
                items(rows, key = { it.itemKey }) { row ->
                    when (row) {
                        is SessionRow.Ride -> SessionCard(
                            listing = row.listing,
                            selected = row.listing.baseName in state.selected,
                            onClick = {
                                if (selecting && !row.listing.isOpen) {
                                    onIntent(SessionsUiIntent.ToggleSelected(row.listing.baseName))
                                } else if (!selecting) {
                                    onIntent(SessionsUiIntent.Open(row.listing.baseName))
                                }
                            },
                            // The open session is still being written and cannot be exported.
                            onLongClick = if (row.listing.isOpen) null else {
                                { onIntent(SessionsUiIntent.ToggleSelected(row.listing.baseName)) }
                            }
                        )

                        is SessionRow.Attempts -> AttemptsCard(
                            row = row,
                            onToggle = { onIntent(SessionsUiIntent.ToggleAttempts(row.key)) }
                        )
                    }
                }
            }
        }
    }
}

private val SessionRow.itemKey: String
    get() = when (this) {
        is SessionRow.Ride -> listing.baseName
        is SessionRow.Attempts -> "attempts-$key"
    }

@Composable
private fun SessionCard(
    listing: SessionListing,
    selected: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val summary = listing.summary
    val clicks = if (onClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(clicks),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (listing.isOpen || summary == null) {
                        stringResource(R.string.sessions_open)
                    } else {
                        openedAtText(summary.openedAt) ?: listing.baseName
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (!summary?.attention.isNullOrEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (summary != null) SummaryLines(summary)
        }
    }
}

@Composable
private fun SummaryLines(summary: SessionSummary) {
    val meta = listOfNotNull(
        summary.durationMs?.let {
            stringResource(
                R.string.sessions_duration,
                (it / 60_000).toInt()
            )
        },
        summary.origin
    )
    if (meta.isNotEmpty()) {
        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    }
    if (summary.attention.isNotEmpty()) {
        Text(
            text = summary.attention.joinToString(" · ") { "⚠ ${it.name}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    summary.failure?.let {
        Text(
            text = stringResource(R.string.sessions_summary_failed, it.exception),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (summary.notes.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            summary.notes.forEach { note ->
                AssistChip(onClick = {}, label = { Text(note) }, enabled = false)
            }
        }
    }
    Text(
        text = stringResource(
            R.string.sessions_line,
            summary.projectionFrames,
            summary.rejections.size,
            summary.sabModeLatencyMs?.let {
                stringResource(
                    R.string.sessions_screen_after,
                    screenLatencySeconds(it, currentLocale())
                )
            } ?: stringResource(R.string.sessions_screen_never),
            pluralStringResource(R.plurals.sessions_calls, summary.calls, summary.calls)
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AttemptsCard(
    row: SessionRow.Attempts,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.sessions_attempts,
                    row.listings.size,
                    row.listings.size
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (row.expanded) {
                row.listings.forEach { attempt ->
                    val summary = attempt.summary
                    Text(
                        text = listOfNotNull(
                            openedAtText(summary?.openedAt) ?: attempt.baseName,
                            summary?.closeReason
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetail(
    listing: SessionListing,
    onIntent: (SessionsUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = listing.summary
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(listing.baseName, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { onIntent(SessionsUiIntent.CloseDetail) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "card") {
                SessionCard(listing = listing, selected = false, onClick = null, onLongClick = null)
            }
            if (!listing.isOpen) {
                item(key = "share") {
                    Button(onClick = { onIntent(SessionsUiIntent.ShareDetail) }) {
                        Text(stringResource(R.string.sessions_share_this))
                    }
                }
            }
            if (summary != null) {
                item(key = "divider") { HorizontalDivider() }
                items(summaryFields(summary), key = { it.first }) { (name, value) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1.4f)
                        )
                    }
                }
            }
        }
    }
}

/** The zone of the phone, not of the file: the rider reads it on this phone, now. */
@Composable
private fun openedAtText(openedAt: String?): String? {
    val locale = currentLocale()
    return openedAt?.let {
        try {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale)
                .format(Instant.parse(it).atZone(ZoneId.systemDefault()))
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

@Composable
private fun currentLocale(): Locale = LocalLocale.current.platformLocale

@Preview(name = "Light Mode", showBackground = true)
@Preview(name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Spanish", locale = "es", showBackground = true)
@Composable
private fun SessionsScreenPreview(
    @PreviewParameter(SessionsScreenPreviewProvider::class) state: SessionsUiState
) {
    OpenSABTheme {
        SessionsScreen(state = state, onIntent = {})
    }
}
