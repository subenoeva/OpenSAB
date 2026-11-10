package com.opensab.domain.autoconnect

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoConnectEvaluatorTest {

    private val knownMac = "02:00:00:00:00:AB"
    private val knownMacs = setOf(knownMac)

    @Test
    fun shouldReturnConnectWhenAclConnectedForKnownHondaBtu() {
        val decision = AutoConnectEvaluator.evaluate(
            action = "android.bluetooth.device.action.ACL_CONNECTED",
            deviceName = "HONDA BTU 73FD",
            deviceAddress = knownMac,
            knownVehicleMacs = knownMacs,
            autoConnectEnabled = true
        )
        assertEquals(AutoConnectDecision.Connect(knownMac), decision)
    }

    @Test
    fun shouldReturnConnectWhenNameHasDifferentCase() {
        val decision = AutoConnectEvaluator.evaluate(
            action = "android.bluetooth.device.action.ACL_CONNECTED",
            deviceName = "Honda Btu 73fd",
            deviceAddress = "02:00:00:00:00:ab",
            knownVehicleMacs = knownMacs,
            autoConnectEnabled = true
        )
        assertEquals(AutoConnectDecision.Connect(knownMac), decision)
    }

    @Test
    fun shouldReturnIgnoreWhenDeviceIsNotHondaBtu() {
        val decision = AutoConnectEvaluator.evaluate(
            action = "android.bluetooth.device.action.ACL_CONNECTED",
            deviceName = "Sony WH-1000XM4",
            deviceAddress = "11:22:33:44:55:66",
            knownVehicleMacs = knownMacs,
            autoConnectEnabled = true
        )
        assertEquals(AutoConnectDecision.Ignore, decision)
    }

    @Test
    fun shouldReturnIgnoreWhenHondaBtuIsNotInKnownVehicles() {
        val decision = AutoConnectEvaluator.evaluate(
            action = "android.bluetooth.device.action.ACL_CONNECTED",
            deviceName = "HONDA BTU 9999",
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            knownVehicleMacs = knownMacs,
            autoConnectEnabled = true
        )
        assertEquals(AutoConnectDecision.Ignore, decision)
    }

    @Test
    fun shouldReturnIgnoreWhenAutoConnectIsDisabled() {
        val decision = AutoConnectEvaluator.evaluate(
            action = "android.bluetooth.device.action.ACL_CONNECTED",
            deviceName = "HONDA BTU 73FD",
            deviceAddress = knownMac,
            knownVehicleMacs = knownMacs,
            autoConnectEnabled = false
        )
        assertEquals(AutoConnectDecision.Ignore, decision)
    }

    @Test
    fun shouldReturnDisconnectWhenAclDisconnectedMatchesActiveSession() {
        val decision = AutoConnectEvaluator.evaluate(
            action = "android.bluetooth.device.action.ACL_DISCONNECTED",
            deviceName = "HONDA BTU 73FD",
            deviceAddress = knownMac,
            knownVehicleMacs = knownMacs,
            autoConnectEnabled = true,
            activeSessionMac = knownMac
        )
        assertEquals(AutoConnectDecision.Disconnect(knownMac), decision)
    }

    @Test
    fun shouldReturnIgnoreWhenAclDisconnectedDoesNotMatchActiveSession() {
        val decision = AutoConnectEvaluator.evaluate(
            action = "android.bluetooth.device.action.ACL_DISCONNECTED",
            deviceName = "Some Other Device",
            deviceAddress = "00:11:22:33:44:55",
            knownVehicleMacs = knownMacs,
            autoConnectEnabled = true,
            activeSessionMac = knownMac
        )
        assertEquals(AutoConnectDecision.Ignore, decision)
    }

    @Test
    fun shouldReturnIgnoreWhenDeviceAddressIsNull() {
        val decision = AutoConnectEvaluator.evaluate(
            action = "android.bluetooth.device.action.ACL_CONNECTED",
            deviceName = "HONDA BTU 73FD",
            deviceAddress = null,
            knownVehicleMacs = knownMacs,
            autoConnectEnabled = true
        )
        assertEquals(AutoConnectDecision.Ignore, decision)
    }

    @Test
    fun shouldReturnIgnoreWhenAutoDisconnectIsDisabled() {
        val decision = AutoConnectEvaluator.evaluate(
            action = "android.bluetooth.device.action.ACL_DISCONNECTED",
            deviceName = "HONDA BTU 73FD",
            deviceAddress = knownMac,
            knownVehicleMacs = knownMacs,
            autoConnectEnabled = true,
            activeSessionMac = knownMac,
            autoDisconnectEnabled = false
        )
        assertEquals(AutoConnectDecision.Ignore, decision)
    }

    @Test
    fun shouldStillDisconnectWhenOnlyAutoConnectIsDisabled() {
        val decision = AutoConnectEvaluator.evaluate(
            action = "android.bluetooth.device.action.ACL_DISCONNECTED",
            deviceName = "HONDA BTU 73FD",
            deviceAddress = knownMac,
            knownVehicleMacs = knownMacs,
            autoConnectEnabled = false,
            activeSessionMac = knownMac,
            autoDisconnectEnabled = true
        )
        assertEquals(
            "Turning off automatic connecting must not strand a live session",
            AutoConnectDecision.Disconnect(knownMac),
            decision
        )
    }

    @Test
    fun shouldReturnIgnoreWhenActionIsUnknown() {
        val decision = AutoConnectEvaluator.evaluate(
            action = "some.random.action",
            deviceName = "HONDA BTU 73FD",
            deviceAddress = knownMac,
            knownVehicleMacs = knownMacs,
            autoConnectEnabled = true
        )
        assertEquals(AutoConnectDecision.Ignore, decision)
    }
}
