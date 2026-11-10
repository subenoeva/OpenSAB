package com.opensab.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opensab.app.R
import com.opensab.domain.settings.SessionLogLimit

@Composable
internal fun JournalSection(
    journal: JournalSettingsUi,
    onIntent: (SettingsUiIntent) -> Unit
) {
    SettingsCategory(
        title = stringResource(R.string.settings_category_journal),
        icon = Icons.Default.Folder
    ) {
        JournalLimitSelector(
            journal = journal,
            onSelect = { mb -> onIntent(SettingsUiIntent.SetSessionLogLimit(mb)) }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onIntent(SettingsUiIntent.OpenSessions) }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_journal_open),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun JournalLimitSelector(
    journal: JournalSettingsUi,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_journal_limit_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        val locale = LocalLocale.current.platformLocale
        Text(
            text = stringResource(
                if (journal.overLimit) {
                    R.string.settings_journal_usage_over
                } else {
                    R.string.settings_journal_usage
                },
                journal.usedMegabytes(locale),
                journal.limitMb
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (journal.overLimit) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SessionLogLimit.OPTIONS_MB.forEach { option ->
                FilterChip(
                    selected = journal.limitMb == option,
                    onClick = { onSelect(option) },
                    label = {
                        Text(stringResource(R.string.settings_journal_limit_option, option))
                    }
                )
            }
        }
    }
}
