package com.opensab.app.ui.permissions

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensab.app.ui.common.ObserveAsEvents
import timber.log.Timber

/**
 * The stateful half of the permissions screen, and the only place in the app that launches a
 * permission request or a system screen.
 *
 * The `Intent` is built here, not in the ViewModel: the event carries a [PermissionId] and this
 * launches the first [SystemScreenPolicy] candidate that resolves against the `PackageManager`.
 *
 * Every launcher callback sends `Refresh` instead of reading the result map:
 * `StartActivityForResult` over a settings screen hands back a `RESULT_CANCELED` that says
 * nothing, so the status is always read again.
 */
@Composable
fun PermissionsRoute(
    isIntro: Boolean,
    onNavigateToScan: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: PermissionsViewModel =
        hiltViewModel<PermissionsViewModel, PermissionsViewModel.Factory>(
            creationCallback = { factory -> factory.create(isIntro) }
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.sendIntent(PermissionsUiIntent.Refresh)
    }
    val systemScreenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.sendIntent(PermissionsUiIntent.Refresh)
    }

    // Several rows can only change outside the app and none of them reports a usable result, so
    // coming back to the foreground is the one reliable moment to read them again: the return from
    // a system screen, a revocation made while in the background, and the first paint.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.sendIntent(PermissionsUiIntent.Refresh)
    }

    ObserveAsEvents(viewModel.uiEvents) { event ->
        when (event) {
            is PermissionsUiEvent.RequestRuntime ->
                runtimeLauncher.launch(event.permissions.toTypedArray())

            is PermissionsUiEvent.OpenSystemScreen -> {
                val intent = context.resolveSystemScreen(event.id)
                if (intent == null) {
                    Timber.w("No system screen resolves for %s on this phone", event.id)
                } else {
                    systemScreenLauncher.launch(intent)
                }
            }

            is PermissionsUiEvent.NavigateToScan -> onNavigateToScan()
            is PermissionsUiEvent.NavigateBack -> onNavigateBack()
        }
    }

    PermissionsScreen(
        state = state,
        onIntent = viewModel::sendIntent,
        modifier = modifier
    )
}

/**
 * The first candidate of [SystemScreenPolicy] that this phone can actually open. The direct battery
 * request does not resolve on every OEM skin, hence the fallback list.
 */
private fun Context.resolveSystemScreen(id: PermissionId): Intent? =
    SystemScreenPolicy.screensFor(id, packageName)
        .map { it.toIntent() }

        .firstOrNull { it.resolveActivity(packageManager) != null }

/**
 * **`BatteryLife` is suppressed on purpose.** Play prohibits a direct exemption request *"unless
 * the core function of the app is adversely affected"*, and OpenSAB's core function is
 * auto-connect: a phone free to doze through the ACL receiver and `SabConnectionService` runs
 * neither. There is no deferrable job for WorkManager, only a GATT link that is up when the key
 * turns or worth nothing.
 *
 * This does **not** claim the peripheral-companion row of Google's table: that row is qualified by
 * *"for the purpose of providing the peripheral device internet access"*, and the cluster gets no
 * internet. The case rests on the general rule, and it is arguable rather than named.
 *
 * The escape hatch costs one line: `SystemScreenPolicy` lists `BatteryOptimizationList` second,
 * which needs no manifest declaration, so deleting `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` makes it
 * the only candidate. Pull it on a real Play submission or rejection, not before: that flow makes
 * the rider hunt for the app in a list of every installed one.
 */
@SuppressLint("BatteryLife")
private fun SystemScreenTarget.toIntent(): Intent = when (this) {
    is SystemScreenTarget.NotificationListenerSettings ->
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    is SystemScreenTarget.BatteryExemptionRequest ->
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData("package:$packageName".toUri())

    is SystemScreenTarget.BatteryOptimizationList ->
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    is SystemScreenTarget.ManageOverlayPermission ->
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())

    is SystemScreenTarget.BluetoothSettings -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    is SystemScreenTarget.LocationSourceSettings -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
}
