package com.opensab.app.ui.scan

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.opensab.app.R
import com.opensab.app.theme.OpenSABTheme
import com.opensab.app.ui.common.icon

@Composable
fun VehicleScanScreen(
    state: VehicleScanUiState,
    onIntent: (VehicleScanUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    // The rider grants it on the permissions screen or in Android's settings, so coming back to
    // the foreground is the one reliable moment to read it again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onIntent(VehicleScanUiIntent.RefreshPermissions)
    }

    // Everything above the list keeps clear of the system bars, and the list does not: the cards
    // scroll under the navigation bar, and the bottom inset is spent on the list's contentPadding
    // so the last card comes to rest above the bar rather than behind it.
    val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                    )
                )
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.scan_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(onClick = { onIntent(VehicleScanUiIntent.OpenSettings) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.cd_settings),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Error Banner
            val error = state.error
            if (error != null) {
                Spacer(modifier = Modifier.height(16.dp))
                ErrorBanner(
                    message = error.resolved(),
                    onDismiss = { onIntent(VehicleScanUiIntent.DismissError) }
                )
            }

            // Missing the Bluetooth permission is not an error that happened: it is a state the
            // rider can leave, so it is said with a way out and not with a red banner.
            if (!state.blePermissionsGranted) {
                Spacer(modifier = Modifier.height(16.dp))
                PermissionsBanner(
                    onGrant = { onIntent(VehicleScanUiIntent.OpenPermissions) }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Scan Action Button. Disabled without the permission rather than throwing to paint an
            // error: the banner above already says what is missing.
            Button(
                onClick = { onIntent(state.scanToggleIntent()) },
                enabled = state.blePermissionsGranted,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = if (state.isScanning) Icons.AutoMirrored.Filled.BluetoothSearching else Icons.Default.Bluetooth,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (state.isScanning) stringResource(R.string.scan_button_stop) else stringResource(R.string.scan_button_start))
            }

            Spacer(modifier = Modifier.height(24.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp + bottomInset)
            ) {
                // Known Vehicles Section
                if (state.knownVehicles.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.scan_saved_motorcycles, state.knownVehicles.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(state.knownVehicles) { vehicle ->
                        val scannedDevice =
                            vehicle.asScannedDevice(fallbackName = stringResource(R.string.device_honda_motorcycle))
                        DeviceCard(
                            device = scannedDevice,
                            leadingIcon = vehicle.bodyStyle.icon(),
                            isPairing = state.isPairing(vehicle.macAddress),
                            isEnabled = state.canConnect,
                            onConnect = { onIntent(VehicleScanUiIntent.ConnectToDevice(scannedDevice)) },
                            onCancel = { onIntent(VehicleScanUiIntent.CancelConnect) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Nearby Devices Section
                item {
                    Text(
                        text = stringResource(R.string.scan_nearby_devices, state.scannedDevices.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                if (state.scannedDevices.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (state.isScanning) stringResource(R.string.scan_searching_placeholder) else stringResource(R.string.scan_idle_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(state.scannedDevices) { device ->
                        DeviceCard(
                            device = device,
                            leadingIcon = null,
                            isPairing = state.isPairing(device.address),
                            isEnabled = state.canConnect,
                            onConnect = { onIntent(VehicleScanUiIntent.ConnectToDevice(device)) },
                            onCancel = { onIntent(VehicleScanUiIntent.CancelConnect) }
                        )
                    }
                }
            }
        }
    }

    if (state.askClusterFor != null) {
        ClusterQuestionDialog(
            onAnswer = { onIntent(VehicleScanUiIntent.AnswerClusterDisplay(it)) },
            onDismiss = { onIntent(VehicleScanUiIntent.DismissClusterQuestion) }
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
private fun VehicleScanScreenPreview(
    @PreviewParameter(VehicleScanScreenPreviewProvider::class) state: VehicleScanUiState
) {
    OpenSABTheme {
        VehicleScanScreen(
            state = state,
            onIntent = {}
        )
    }
}
