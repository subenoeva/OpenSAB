package com.opensab.app.ui.permissions

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.opensab.app.R
import com.opensab.app.theme.OpenSABTheme

/**
 * There is no `if` on the kind of a row inside the row: everything that varies comes out of the
 * [PermissionRow] through lookup tables, which is what lets the catalogue be tested without Android.
 *
 * The essential/optional grouping does not reorder anything: the essential entries are already the
 * first ones of `PermissionCatalog.entriesFor`, so a stable partition keeps the catalogue order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    state: PermissionsUiState,
    onIntent: (PermissionsUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val (essential, optional) = state.rows.partition { it.entry.essential }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.permission_screen_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    // No back arrow on the first run: there is nothing behind this screen yet.
                    if (!state.isIntro) {
                        IconButton(onClick = { onIntent(PermissionsUiIntent.NavigateBack) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (state.isIntro) {
                PermissionsFooter(onContinue = { onIntent(PermissionsUiIntent.Continue) })
            }
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 24.dp
            )
        ) {
            if (state.isIntro) {
                item(key = "intro") { PermissionsIntroHeader() }
            }
            item(key = "summary") { PermissionsSummaryCard(rows = state.rows) }

            permissionGroup(
                titleRes = R.string.permission_group_essential,
                captionRes = R.string.permission_group_essential_caption,
                rows = essential,
                onIntent = onIntent
            )
            permissionGroup(
                titleRes = R.string.permission_group_optional,
                captionRes = R.string.permission_group_optional_caption,
                rows = optional,
                onIntent = onIntent
            )
        }
    }
}

/**
 * A heading and its rows, or nothing at all: `entriesFor` depends on the API level, and a level
 * could leave a group empty.
 */
private fun LazyListScope.permissionGroup(
    @StringRes titleRes: Int,
    @StringRes captionRes: Int,
    rows: List<PermissionRow>,
    onIntent: (PermissionsUiIntent) -> Unit
) {
    if (rows.isEmpty()) return

    item(key = "group-$titleRes") {
        PermissionGroupHeader(titleRes = titleRes, captionRes = captionRes)
    }
    items(rows, key = { it.entry.id }) { row ->
        PermissionRowItem(
            row = row,
            onClick = { onIntent(PermissionsUiIntent.Grant(row.entry.id)) }
        )
    }
}

@Composable
private fun PermissionsIntroHeader(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = 8.dp, bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.permission_intro_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.permission_intro_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionGroupHeader(
    @StringRes titleRes: Int,
    @StringRes captionRes: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(captionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * No "Skip" beside "Continue": both would do the same thing.
 *
 * The navigation-bar padding is the footer's own: the app is edge to edge, and `Scaffold` leaves a
 * hand-rolled bottom bar to consume that inset itself.
 */
@Composable
private fun PermissionsFooter(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.permission_intro_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.permission_continue))
            }
        }
    }
}

@Preview(name = "Permissions", showBackground = true)
@Preview(
    name = "Permissions (dark)",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun PermissionsScreenPreview(
    @PreviewParameter(PermissionsScreenPreviewProvider::class) state: PermissionsUiState
) {
    OpenSABTheme {
        PermissionsScreen(state = state, onIntent = {})
    }
}
