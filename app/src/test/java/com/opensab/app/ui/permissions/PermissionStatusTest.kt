package com.opensab.app.ui.permissions

import com.opensab.domain.call.CallAccessPolicy
import com.opensab.domain.call.CallAccessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Every row across the API levels, against a doubled probe: the facts enter through a port. */
class PermissionStatusTest {

    private val bluetoothScan = "android.permission.BLUETOOTH_SCAN"
    private val bluetoothConnect = "android.permission.BLUETOOTH_CONNECT"
    private val recordAudio = "android.permission.RECORD_AUDIO"
    private val answerPhoneCalls = "android.permission.ANSWER_PHONE_CALLS"

    private fun statusOf(probe: PermissionProbe, id: PermissionId): PermissionStatus {
        val entry = PermissionCatalog.entriesFor(probe.sdkInt).single { it.id == id }
        return DefaultPermissionStatusReader(probe).statusOf(entry)
    }

    @Test
    fun `should map a granted runtime permission to granted`() {
        val probe = FakePermissionProbe(granted = setOf(recordAudio))

        assertEquals(PermissionStatus.GRANTED, statusOf(probe, PermissionId.MICROPHONE))
    }

    @Test
    fun `should deny a runtime entry when any of its permissions is missing`() {
        val probe = FakePermissionProbe(sdkInt = 31, granted = setOf(bluetoothScan))

        assertEquals(PermissionStatus.DENIED, statusOf(probe, PermissionId.BLUETOOTH))

        probe.granted = setOf(bluetoothScan, bluetoothConnect)

        assertEquals(PermissionStatus.GRANTED, statusOf(probe, PermissionId.BLUETOOTH))
    }

    @Test
    fun `should read notification access from the listener and not from a permission`() {
        val probe = FakePermissionProbe(granted = emptySet(), notificationListenerEnabled = true)

        assertEquals(PermissionStatus.GRANTED, statusOf(probe, PermissionId.NOTIFICATION_ACCESS))
    }

    @Test
    fun `should read the battery exemption from the power manager`() {
        val probe = FakePermissionProbe(ignoringBatteryOptimizations = true)

        assertEquals(PermissionStatus.GRANTED, statusOf(probe, PermissionId.BATTERY_UNRESTRICTED))

        probe.ignoringBatteryOptimizations = false

        assertEquals(PermissionStatus.DENIED, statusOf(probe, PermissionId.BATTERY_UNRESTRICTED))
    }

    @Test
    fun `should read the overlay permission from the probe`() {
        val probe = FakePermissionProbe(canDrawOverlays = true)

        assertEquals(PermissionStatus.GRANTED, statusOf(probe, PermissionId.DRAW_OVERLAYS))

        probe.canDrawOverlays = false

        assertEquals(PermissionStatus.DENIED, statusOf(probe, PermissionId.DRAW_OVERLAYS))
    }

    @Test
    fun `should read the radio from the adapter`() {
        val probe = FakePermissionProbe(bluetoothEnabled = true)

        assertEquals(PermissionStatus.GRANTED, statusOf(probe, PermissionId.BLUETOOTH_ENABLED))

        probe.bluetoothEnabled = false

        assertEquals(PermissionStatus.DENIED, statusOf(probe, PermissionId.BLUETOOTH_ENABLED))
    }

    @Test
    fun `should read the location services from the location manager`() {
        val probe = FakePermissionProbe(locationServicesEnabled = true)

        assertEquals(PermissionStatus.GRANTED, statusOf(probe, PermissionId.LOCATION_SERVICES))

        probe.locationServicesEnabled = false

        assertEquals(PermissionStatus.DENIED, statusOf(probe, PermissionId.LOCATION_SERVICES))
    }

    /**
     * Without `Notification$CallStyle` there is no route at all, so granting the permission buys
     * nothing and the row has to say so rather than invite a useless tap.
     */
    @Test
    fun `should report telephony as unsupported below api 31`() {
        for (sdkInt in listOf(26, 30)) {
            val probe = FakePermissionProbe(
                sdkInt = sdkInt,
                granted = setOf(answerPhoneCalls),
                notificationListenerEnabled = true
            )

            assertEquals(
                "API $sdkInt",
                PermissionStatus.UNSUPPORTED,
                statusOf(probe, PermissionId.ANSWER_CALLS)
            )
        }
    }

    @Test
    fun `should let the permission decide telephony from api 31`() {
        for (sdkInt in listOf(31, 33, 37)) {
            val probe = FakePermissionProbe(sdkInt = sdkInt, granted = setOf(answerPhoneCalls))

            assertEquals(
                "API $sdkInt",
                PermissionStatus.GRANTED,
                statusOf(probe, PermissionId.ANSWER_CALLS)
            )

            probe.granted = emptySet()

            assertEquals(
                "API $sdkInt",
                PermissionStatus.DENIED,
                statusOf(probe, PermissionId.ANSWER_CALLS)
            )
        }
    }

    /**
     * Notification access is the grant that holds up music, calls, messages and turn-by-turn;
     * painting it unreachable on the API levels below 31 would leave the rider no way to fix it
     * from inside the app.
     */
    @Test
    fun `should keep notification access grantable below api 31`() {
        for (sdkInt in listOf(26, 30)) {
            for (enabled in listOf(false, true)) {
                val probe = FakePermissionProbe(
                    sdkInt = sdkInt,
                    notificationListenerEnabled = enabled
                )

                assertNotEquals(
                    "API $sdkInt, listener=$enabled",
                    PermissionStatus.UNSUPPORTED,
                    statusOf(probe, PermissionId.NOTIFICATION_ACCESS)
                )
            }
        }
    }

    /** The API 31 rule is `CallAccessPolicy`'s; this row consumes it, not restates it. */
    @Test
    fun `should delegate the telephony rule instead of restating it`() {
        for (sdkInt in listOf(26, 30, 31, 33, 37)) {
            for (listener in listOf(false, true)) {
                for (answer in listOf(false, true)) {
                    val probe = FakePermissionProbe(
                        sdkInt = sdkInt,
                        granted = if (answer) setOf(answerPhoneCalls) else emptySet(),
                        notificationListenerEnabled = listener
                    )
                    val expected = when (CallAccessPolicy.resolve(sdkInt, listener)) {
                        CallAccessState.UNSUPPORTED -> PermissionStatus.UNSUPPORTED
                        else -> if (answer) PermissionStatus.GRANTED else PermissionStatus.DENIED
                    }

                    assertEquals(
                        "API $sdkInt, listener=$listener, answer=$answer",
                        expected,
                        statusOf(probe, PermissionId.ANSWER_CALLS)
                    )
                }
            }
        }
    }

    @Test
    fun `should resolve every entry of the catalog on every api level`() {
        for (sdkInt in listOf(26, 30, 31, 33, 37)) {
            val probe = FakePermissionProbe(sdkInt = sdkInt)
            val reader = DefaultPermissionStatusReader(probe)
            val entries = PermissionCatalog.entriesFor(sdkInt)

            assertEquals(
                "API $sdkInt",
                entries.size,
                entries.count { reader.statusOf(it) in PermissionStatus.entries }
            )
        }
    }

    /**
     * A special access and a system toggle have no permission to look up, so asking for one would
     * mean the rule had grown a branch the catalogue already answers.
     */
    @Test
    fun `should not ask the probe for permissions of a non runtime entry`() {
        val probe = FakePermissionProbe()
        val reader = DefaultPermissionStatusReader(probe)

        PermissionCatalog.entriesFor(probe.sdkInt)
            .filter { it.kind != PermissionKind.RUNTIME }
            .forEach { reader.statusOf(it) }

        assertEquals(emptyList<String>(), probe.permissionLookups)
    }
}
