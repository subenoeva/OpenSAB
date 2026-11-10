package com.opensab.app.ui

import com.opensab.domain.settings.AppSettings
import com.opensab.sab.protocol.vehicle.DestinationId
import com.opensab.sab.protocol.vehicle.SwitchType
import com.opensab.sab.protocol.vehicle.VehicleInformation
import com.opensab.sab.session.ConnectionStep
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.crypto.SecuritySession
import com.opensab.sab.session.vehicle.KnownVehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOnPolicyTest {

    private val sampleVehicle = KnownVehicle(
        macAddress = "AA:BB:CC:DD:EE:FF",
        displayName = "Honda Forza 750",
        vehicleInfo = VehicleInformation(
            vehicleType = 0x01,
            displayCapability = byteArrayOf(0x01, 0x02),
            switchType = SwitchType.TYPE_B,
            categoryId = 0x01,
            seriesCode = "RH10",
            destinationId = DestinationId.EU
        )
    )

    private val sampleSecuritySession = SecuritySession(
        macAddress = "AA:BB:CC:DD:EE:FF",
        aesKey = ByteArray(16)
    )

    private val connectedState = SabSessionState.Connected(
        vehicle = sampleVehicle,
        session = sampleSecuritySession
    )

    @Test
    fun shouldKeepScreenOnWhenPreferenceIsEnabledAndBleIsConnected() {
        val settings = AppSettings(keepScreenOnWhileRiding = true)
        assertTrue(ScreenOnPolicy.shouldKeepScreenOn(settings, connectedState))
        assertTrue(ScreenOnPolicy.shouldKeepScreenOn(keepScreenOnWhileRiding = true, isConnected = true))
    }

    @Test
    fun shouldNotKeepScreenOnWhenPreferenceIsDisabledEvenIfBleIsConnected() {
        val settings = AppSettings(keepScreenOnWhileRiding = false)
        assertFalse(ScreenOnPolicy.shouldKeepScreenOn(settings, connectedState))
        assertFalse(ScreenOnPolicy.shouldKeepScreenOn(keepScreenOnWhileRiding = false, isConnected = true))
    }

    @Test
    fun shouldNotKeepScreenOnWhenDisconnectedEvenIfPreferenceIsEnabled() {
        val settings = AppSettings(keepScreenOnWhileRiding = true)
        assertFalse(ScreenOnPolicy.shouldKeepScreenOn(settings, SabSessionState.Disconnected))
        assertFalse(ScreenOnPolicy.shouldKeepScreenOn(keepScreenOnWhileRiding = true, isConnected = false))
    }

    @Test
    fun shouldNotKeepScreenOnWhileConnectingOrOnError() {
        val settings = AppSettings(keepScreenOnWhileRiding = true)
        assertFalse(
            ScreenOnPolicy.shouldKeepScreenOn(
                settings,
                SabSessionState.Connecting(ConnectionStep.NEGOTIATING_MTU)
            )
        )
        assertFalse(
            ScreenOnPolicy.shouldKeepScreenOn(
                settings,
                SabSessionState.Error(IllegalStateException("Simulated BLE drop"))
            )
        )
    }

    @Test
    fun shouldDynamicallyUpdateScreenOnStateAsFlowEmitsChanges() = runTest {
        val settingsFlow = MutableStateFlow(AppSettings(keepScreenOnWhileRiding = true))
        val sessionStateFlow = MutableStateFlow<SabSessionState>(SabSessionState.Disconnected)

        val screenOnFlow = combine(settingsFlow, sessionStateFlow) { settings, session ->
            ScreenOnPolicy.shouldKeepScreenOn(settings, session)
        }

        // Initially disconnected -> false
        assertFalse(screenOnFlow.first())

        // BLE connects -> true
        sessionStateFlow.value = connectedState
        assertTrue(screenOnFlow.first())

        // User turns off keepScreenOnWhileRiding in settings -> false
        settingsFlow.value = AppSettings(keepScreenOnWhileRiding = false)
        assertFalse(screenOnFlow.first())

        // User turns setting back on -> true
        settingsFlow.value = AppSettings(keepScreenOnWhileRiding = true)
        assertTrue(screenOnFlow.first())

        // BLE disconnects -> false
        sessionStateFlow.value = SabSessionState.Disconnected
        assertFalse(screenOnFlow.first())
    }

    @Test
    fun showWhenLockedFollowsTheSetting() {
        assertTrue(ScreenOnPolicy.showWhenLocked(AppSettings(showOnLockScreen = true)))
        assertFalse(ScreenOnPolicy.showWhenLocked(AppSettings(showOnLockScreen = false)))
    }
}
