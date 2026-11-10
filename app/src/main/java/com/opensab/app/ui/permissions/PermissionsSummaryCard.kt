package com.opensab.app.ui.permissions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opensab.app.R

@Composable
internal fun PermissionsSummaryCard(
    rows: List<PermissionRow>,
    modifier: Modifier = Modifier
) {
    val progress = rows.progress()
    val blocking = progress.tone == PermissionsTone.BLOCKING
    val complete = progress.tone == PermissionsTone.COMPLETE

    val scheme = MaterialTheme.colorScheme
    val accent = when {
        blocking -> scheme.error
        complete -> scheme.primary
        else -> scheme.secondary
    }
    val accentContainer = when {
        blocking -> scheme.errorContainer
        complete -> scheme.primaryContainer
        else -> scheme.secondaryContainer
    }
    val onAccentContainer = when {
        blocking -> scheme.onErrorContainer
        complete -> scheme.onPrimaryContainer
        else -> scheme.onSecondaryContainer
    }
    val icon = when {
        blocking -> Icons.Default.Warning
        complete -> Icons.Default.CheckCircle
        else -> Icons.Default.Info
    }
    val titleRes = when {
        blocking -> R.string.permission_summary_blocking
        complete -> R.string.permission_summary_all_granted
        else -> R.string.permission_summary_partial
    }
    val descriptionRes = when {
        blocking -> R.string.permission_summary_blocking_desc
        complete -> R.string.permission_summary_all_granted_desc
        else -> R.string.permission_summary_partial_desc
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = icon, container = accentContainer, tint = onAccentContainer)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (blocking) scheme.error else scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.permission_summary_count, progress.granted, progress.grantable),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                color = accent,
                trackColor = scheme.surfaceContainerLowest,
                drawStopIndicator = {}
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}
