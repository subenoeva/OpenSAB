package com.opensab.app.ui.permissions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SettingsBluetooth
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The description says **what is lost**, in a form that stays true once the row is granted,
 * because the same sentence is shown either way.
 *
 * **Only a `DENIED` row is clickable.** `RequestMultiplePermissions` over a permission already held
 * returns at once without drawing a dialog, so a clickable `GRANTED` row would be a dead control.
 * Without the click there is no `Role.Button` either, so the screen reader matches what is shown.
 */
@Composable
internal fun PermissionRowItem(
    row: PermissionRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = permissionRowColors(row)
    val actionable = row.isActionable
    val actionLabel = stringResource(row.entry.kind.actionRes())

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (actionable) {
                    Modifier.clickable(
                        onClickLabel = actionLabel,
                        role = Role.Button,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            IconBadge(
                icon = row.entry.id.icon(),
                container = colors.iconContainer,
                tint = colors.icon
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(row.entry.id.titleRes()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.title,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusPill(
                        text = stringResource(row.status.labelRes()),
                        container = colors.statusContainer,
                        tint = colors.statusText
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(row.descriptionRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.description
                )
                if (actionable) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.action,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.action,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class PermissionRowColors(
    val container: Color,
    val iconContainer: Color,
    val icon: Color,
    val title: Color,
    val description: Color,
    val statusContainer: Color,
    val statusText: Color,
    val action: Color
)

@Composable
private fun permissionRowColors(row: PermissionRow): PermissionRowColors {
    val scheme = MaterialTheme.colorScheme

    return when (row.tone) {
        PermissionRowTone.BLOCKING -> PermissionRowColors(
            container = scheme.errorContainer,
            iconContainer = scheme.error,
            icon = scheme.onError,
            title = scheme.onErrorContainer,
            description = scheme.onErrorContainer,
            statusContainer = scheme.error,
            statusText = scheme.onError,
            action = scheme.onErrorContainer
        )
        PermissionRowTone.GRANTED -> PermissionRowColors(
            container = scheme.surfaceContainerLow,
            iconContainer = scheme.primaryContainer,
            icon = scheme.onPrimaryContainer,
            title = scheme.onSurface,
            description = scheme.onSurfaceVariant,
            statusContainer = scheme.primaryContainer,
            statusText = scheme.onPrimaryContainer,
            action = scheme.onSurfaceVariant
        )

        PermissionRowTone.UNSUPPORTED -> PermissionRowColors(
            container = scheme.surfaceContainerLow,
            iconContainer = scheme.surfaceContainerHighest,
            icon = scheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
            title = scheme.onSurface.copy(alpha = DISABLED_ALPHA),
            description = scheme.onSurfaceVariant,
            statusContainer = scheme.surfaceContainerHighest,
            statusText = scheme.onSurfaceVariant,
            action = scheme.onSurfaceVariant
        )

        PermissionRowTone.PENDING -> PermissionRowColors(
            container = scheme.surfaceContainerHighest,
            iconContainer = scheme.surface,
            icon = scheme.onSurfaceVariant,
            title = scheme.onSurface,
            description = scheme.onSurfaceVariant,
            statusContainer = scheme.surface,
            statusText = scheme.onSurfaceVariant,
            action = scheme.primary
        )
    }
}

private const val DISABLED_ALPHA = 0.55f

private fun PermissionId.icon(): ImageVector = when (this) {
    PermissionId.BLUETOOTH -> Icons.Default.Bluetooth
    PermissionId.BLUETOOTH_ENABLED -> Icons.Default.SettingsBluetooth
    PermissionId.NOTIFICATION_ACCESS -> Icons.Default.NotificationsActive
    PermissionId.BATTERY_UNRESTRICTED -> Icons.Default.BatteryFull
    PermissionId.DRAW_OVERLAYS -> Icons.Default.Layers
    PermissionId.NOTIFICATIONS -> Icons.Default.Notifications
    PermissionId.LOCATION_WEATHER -> Icons.Default.LocationOn
    PermissionId.LOCATION_SERVICES -> Icons.Default.MyLocation
    PermissionId.MICROPHONE -> Icons.Default.Mic
    PermissionId.CONTACTS -> Icons.Default.Contacts
    PermissionId.PLACE_CALLS -> Icons.Default.Dialpad
    PermissionId.ANSWER_CALLS -> Icons.Default.Call
    PermissionId.SEND_SMS -> Icons.Default.Sms
}
