package com.opensab.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opensab.app.R
import com.opensab.app.ui.common.icon
import com.opensab.app.ui.common.label
import com.opensab.app.ui.common.toCardUi
import com.opensab.sab.session.vehicle.BodyStyle
import com.opensab.sab.session.vehicle.ClusterDisplay
import com.opensab.sab.session.vehicle.KnownVehicle
import com.opensab.sab.session.vehicle.defaultVehicleName

@Composable
internal fun PairedVehiclesSection(
    knownVehicles: List<KnownVehicle>,
    onEdit: (KnownVehicle) -> Unit,
    onDelete: (KnownVehicle) -> Unit
) {
    SettingsCategory(
        title = stringResource(
            R.string.settings_category_paired_vehicles,
            knownVehicles.size
        ),
        icon = Icons.Default.TwoWheeler
    ) {
        if (knownVehicles.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_no_paired_vehicles),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            knownVehicles.forEach { vehicle ->
                PairedVehicleItem(
                    vehicle = vehicle,
                    onEdit = { onEdit(vehicle) },
                    onDelete = { onDelete(vehicle) }
                )
            }
        }
    }
}

@Composable
internal fun ForgetVehicleDialog(
    vehicle: KnownVehicle,
    onIntent: (SettingsUiIntent) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.dialog_forget_vehicle_title)) },
        text = {
            Text(
                stringResource(
                    R.string.dialog_forget_vehicle_desc,
                    vehicle.displayName,
                    vehicle.macAddress
                )
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onIntent(SettingsUiIntent.ForgetVehicle(vehicle.macAddress))
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.dialog_action_forget))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
            }) { Text(stringResource(R.string.dialog_action_cancel)) }
        }
    )
}

@Composable
private fun PairedVehicleItem(
    vehicle: KnownVehicle,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardUi = vehicle.toCardUi()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = vehicle.bodyStyle.icon(),
            contentDescription = stringResource(vehicle.bodyStyle.label()),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cardUi.title.ifBlank { stringResource(R.string.device_honda_motorcycle) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.settings_paired_vehicle_summary,
                    vehicle.macAddress,
                    cardUi.seriesCode ?: stringResource(R.string.value_not_available)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.cd_forget_motorcycle),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Edits the rider's half of a paired vehicle. The draft stays local until Save; a blank name is
 * sent as typed and falls back to the series default when it becomes a profile.
 */
@Composable
internal fun VehicleProfileDialog(
    vehicle: KnownVehicle,
    onSave: (VehicleProfileDraft) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(vehicle.macAddress) { mutableStateOf(vehicle.toDraft()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vehicle_profile_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val used = draft.name.codePointCount(0, draft.name.length)
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { typed ->
                        // Counted in code points so an emoji costs one, as it will in storage. A
                        // paste past the limit is cut, not dropped whole.
                        draft = draft.copy(name = typed.cutToCodePoints(VEHICLE_NAME_MAX_CODE_POINTS))
                    },
                    label = { Text(stringResource(R.string.vehicle_profile_name)) },
                    placeholder = { Text(defaultVehicleName(vehicle.vehicleInfo.seriesCode)) },
                    supportingText = { Text("$used/$VEHICLE_NAME_MAX_CODE_POINTS") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.vehicle_profile_body_style),
                    style = MaterialTheme.typography.titleSmall
                )
                // Wraps rather than scrolls: a chip parked off-screen hid the selected style.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BodyStyle.entries.forEach { style ->
                        val label = stringResource(style.label())
                        FilterChip(
                            selected = draft.bodyStyle == style,
                            onClick = { draft = draft.copy(bodyStyle = style) },
                            label = {
                                Icon(
                                    imageVector = style.icon(),
                                    contentDescription = label,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        )
                    }
                }
                Text(
                    text = stringResource(draft.bodyStyle.label()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.vehicle_profile_cluster),
                    style = MaterialTheme.typography.titleSmall
                )
                ClusterDisplay.entries.forEach { display ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) {
                                draft = draft.copy(clusterDisplay = display)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = draft.clusterDisplay == display, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(
                                when (display) {
                                    ClusterDisplay.COLOR -> R.string.vehicle_profile_cluster_color
                                    ClusterDisplay.SEGMENT -> R.string.vehicle_profile_cluster_segment
                                }
                            )
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.vehicle_profile_cluster_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(draft) }) { Text(stringResource(R.string.dialog_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_action_cancel)) }
        }
    )
}
