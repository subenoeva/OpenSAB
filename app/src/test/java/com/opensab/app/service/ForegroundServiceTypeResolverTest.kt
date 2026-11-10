package com.opensab.app.service

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceTypeResolverTest {

    @Test
    fun belowAndroidQReturnsZero() {
        val type = ForegroundServiceTypeResolver.resolve(
            hasLocationPermission = true,
            hasMicPermission = true,
            sdkInt = Build.VERSION_CODES.P
        )
        assertEquals(0, type)
    }

    @Test
    fun androidQReturnsConnectedDeviceOnly() {
        val type = ForegroundServiceTypeResolver.resolve(
            hasLocationPermission = true,
            hasMicPermission = true,
            sdkInt = Build.VERSION_CODES.Q
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, type)
    }

    /** From Android 11 a service without the microphone type records silence in the background. */
    @Test
    fun androidRToTiramisuAddsTheMicrophoneWhenGranted() {
        val type = ForegroundServiceTypeResolver.resolve(
            hasLocationPermission = true,
            hasMicPermission = true,
            sdkInt = Build.VERSION_CODES.TIRAMISU
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            type
        )
    }

    @Test
    fun androidRToTiramisuOmitsTheMicrophoneWhenDenied() {
        val type = ForegroundServiceTypeResolver.resolve(
            hasLocationPermission = true,
            hasMicPermission = false,
            sdkInt = Build.VERSION_CODES.R
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, type)
    }

    @Test
    fun androidUpsideDownCakeIncludesLocationAndMicWhenGranted() {
        val type = ForegroundServiceTypeResolver.resolve(
            hasLocationPermission = true,
            hasMicPermission = true,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
        val expected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        assertEquals(expected, type)
    }

    @Test
    fun androidUpsideDownCakeOmitsLocationWhenDenied() {
        val type = ForegroundServiceTypeResolver.resolve(
            hasLocationPermission = false,
            hasMicPermission = true,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
        val expected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        assertEquals(expected, type)
    }

    @Test
    fun androidUpsideDownCakeOmitsMicWhenDenied() {
        val type = ForegroundServiceTypeResolver.resolve(
            hasLocationPermission = true,
            hasMicPermission = false,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
        val expected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        assertEquals(expected, type)
    }

    @Test
    fun aStartThatSucceedsObtainsTheRequestedType() {
        val attempts = mutableListOf<Int>()

        val obtained = ForegroundServiceTypeResolver.startWithFallback(0x98) { attempts += it }

        assertEquals(0x98, obtained)
        assertEquals(listOf(0x98), attempts)
    }

    /** The trap at the old log line: it printed what was asked for, 152, after getting 16. */
    @Test
    fun aRefusedStartObtainsConnectedDeviceAndReportsThatInsteadOfTheRequest() {
        val attempts = mutableListOf<Int>()

        val obtained = ForegroundServiceTypeResolver.startWithFallback(0x98) { type ->
            attempts += type
            if (type == 0x98) throw SecurityException("Starting FGS with type microphone requires ...")
        }

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, obtained)
        assertEquals(listOf(0x98, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE), attempts)
    }

    @Test
    fun aRequestThatIsAlreadyTheFallbackIsNotRetried() {
        var attempts = 0

        val thrown = runCatching {
            ForegroundServiceTypeResolver.startWithFallback(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) {
                attempts++
                throw IllegalStateException("boom")
            }
        }.exceptionOrNull()

        assertEquals("boom", thrown?.message)
        assertEquals(1, attempts)
    }
}
