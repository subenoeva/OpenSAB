package com.opensab.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opensab.app.ui.preview.PreviewMockData
import com.opensab.logging.Log
import com.opensab.sab.session.vehicle.VehicleProfile
import com.opensab.sab.session.vehicle.VehicleRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Puts a paired vehicle in storage so the garage screens can be exercised without a motorcycle.
 * Writes both keys, identity and profile, as a real pairing followed by the cluster question would.
 */
@AndroidEntryPoint
class DebugVehicleSeedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var vehicleRepository: VehicleRepository

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SEED_VEHICLE) return
        val vehicle = PreviewMockData.sampleVehicle1
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                vehicleRepository.saveVehicle(vehicle)
                vehicleRepository.saveProfile(
                    vehicle.macAddress,
                    VehicleProfile(vehicle.displayName, vehicle.bodyStyle, vehicle.clusterDisplay)
                )
                Log.i("DebugVehicleSeedReceiver: seeded ${vehicle.macAddress}")
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        const val ACTION_SEED_VEHICLE = "com.opensab.debug.SEED_VEHICLE"
    }
}
