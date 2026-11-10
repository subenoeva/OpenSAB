package com.opensab.app.ui.dashboard

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.opensab.app.R
import com.opensab.app.theme.OpenSABTheme
import com.opensab.app.ui.common.ClusterBadge
import com.opensab.app.ui.common.toCardUi
import com.opensab.app.ui.dashboard.mirror.ClusterMirrorCard
import com.opensab.sab.session.ConnectionStep
import com.opensab.sab.session.SabSessionState

@Composable
fun DashboardLiveScreen(
    state: DashboardUiState,
    onIntent: (DashboardUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val connectedVehicle = state.activeVehicle
    val unknownValue = stringResource(R.string.value_not_available)

    // The rider grants it on the permissions screen or in Android's settings, so coming back to
    // the foreground is the one reliable moment to read it again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onIntent(DashboardUiIntent.RefreshPermissions)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Nothing here scrolls, so there is nothing to send under the bars: the screen just
                // keeps clear of them. Scan spends its bottom inset on the list instead.
                .safeDrawingPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                // Top Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = connectedVehicle?.displayName
                                ?: stringResource(R.string.dashboard_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(
                                R.string.dashboard_model_connected,
                                connectedVehicle?.toCardUi()?.seriesCode ?: unknownValue
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onIntent(DashboardUiIntent.OpenSettings) }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.cd_settings),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TwoWheeler,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50)
                            )
                        }
                    }
                }

                // The rider is normally sent back to the scan screen the moment the link drops,
                // but that navigation only lands while the screen is STARTED: coming back to a
                // backgrounded app shows the dashboard mid-reconnect, and it has to say so.
                SessionProgressBanner(state.sessionState)

                Spacer(modifier = Modifier.height(24.dp))

                // What the cluster is showing right now, and the zones that drive it. The engine
                // owns that state; this only reads it.
                ClusterMirrorCard(
                    state = state.mirrorState,
                    onAction = { functionId ->
                        onIntent(DashboardUiIntent.MirrorAction(functionId))
                    },
                    onRequestHandover = { onIntent(DashboardUiIntent.RequestHandover) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Telemetry Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dashboard_telemetry_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Every value falls back to the not-available marker rather than to a
                        // plausible default, which would be indistinguishable from real telemetry.
                        // The rider-declared cluster is the first thing to check when the
                        // projection looks degraded.
                        InfoRow(
                            label = stringResource(R.string.dashboard_cluster),
                            value = when (connectedVehicle?.toCardUi()?.clusterBadge) {
                                ClusterBadge.COLOR -> stringResource(R.string.vehicle_profile_cluster_color)
                                ClusterBadge.SEGMENT -> stringResource(R.string.vehicle_profile_cluster_segment)
                                else -> unknownValue
                            }
                        )
                        // The DFU firmware lives in `firmwareVersion`; `deviceInfo` is the standard
                        // DIS profile, which nothing reads yet and so is always N/A.
                        InfoRow(
                            label = stringResource(R.string.dashboard_firmware_version),
                            value = connectedVehicle?.firmwareVersion?.versionString ?: unknownValue
                        )
                        InfoRow(
                            label = stringResource(R.string.dashboard_switch_type),
                            value = connectedVehicle?.vehicleInfo?.switchType?.name ?: unknownValue
                        )
                        InfoRow(
                            label = stringResource(R.string.dashboard_destination_id),
                            value = connectedVehicle?.vehicleInfo?.destinationId?.name
                                ?: unknownValue
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Music Widget Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = state.musicState.metadata.title.ifBlank { stringResource(R.string.dashboard_no_media_playing) },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = state.musicState.metadata.artist.ifBlank { stringResource(R.string.dashboard_ready_to_project_audio) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!state.mediaAccessGranted) {
                                MediaAccessPrompt(
                                    onOpenPermissions = {
                                        onIntent(DashboardUiIntent.OpenPermissions)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Disconnect Action Button
            OutlinedButton(
                onClick = { onIntent(DashboardUiIntent.Disconnect) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(imageVector = Icons.Default.PowerSettingsNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.dashboard_disconnect_button))
            }
        }
    }
}

/**
 * Says what the pipeline is doing while the link is not up.
 *
 * `Connecting` names its stage as a [ConnectionStep] rather than a sentence, so the wording of each
 * stage lives in `strings.xml` and not in the service layer.
 */
@Composable
private fun SessionProgressBanner(sessionState: SabSessionState, modifier: Modifier = Modifier) {
    val progressText = when (sessionState) {
        is SabSessionState.Connecting -> stringResource(sessionState.step.labelRes())
        else -> return
    }

    Spacer(modifier = Modifier.height(16.dp))
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.dashboard_connecting_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@StringRes
private fun ConnectionStep.labelRes(): Int = when (this) {
    ConnectionStep.NEGOTIATING_MTU -> R.string.dashboard_step_negotiating_mtu
    ConnectionStep.ESTABLISHING_SECURITY -> R.string.dashboard_step_establishing_security
    ConnectionStep.READING_VEHICLE_IDENTITY -> R.string.dashboard_step_reading_vehicle_identity
    ConnectionStep.ARMING_CHANNELS -> R.string.dashboard_step_arming_channels
    ConnectionStep.RUNNING_ASSIGNMENT -> R.string.dashboard_step_running_assignment
    ConnectionStep.STARTING_ADAPTERS -> R.string.dashboard_step_starting_adapters
    ConnectionStep.WAITING_TO_RETRY -> R.string.dashboard_step_waiting_to_retry
}

/**
 * Says why the music slot is empty. It does not launch the system `Intent` itself: the card
 * explains, the permissions screen asks.
 */
@Composable
private fun MediaAccessPrompt(
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(onClick = onOpenPermissions, modifier = modifier) {
        Text(stringResource(R.string.dashboard_open_permissions))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Preview(name = "Light Mode", showBackground = true)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true
)
@Composable
private fun DashboardLiveScreenPreview(
    @PreviewParameter(DashboardLiveScreenPreviewProvider::class) state: DashboardUiState
) {
    OpenSABTheme {
        DashboardLiveScreen(
            state = state,
            onIntent = {}
        )
    }
}
