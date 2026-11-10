package com.opensab.app.di

import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.FakeSettingsRepository
import com.opensab.domain.settings.Tunable
import com.opensab.sab.protocol.input.DashboardMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the graph hands each owner is the rider's number, clamped, and not the constant it replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
class TunableWiringTest {

    @Test
    fun theWatchdogWaitsWhatTheRiderChose() = runTest {
        val settings = FakeSettingsRepository(AppSettings(sabModeWaitSeconds = 20))
        var reports = 0

        OrchestratorModule.provideSabModeWatchdog(settings)
            .start(MutableStateFlow(DashboardMode.HU_MODE), backgroundScope) { reports++ }

        advanceTimeBy(19.seconds)
        runCurrent()
        assertEquals(0, reports)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, reports)
    }

    @Test
    fun theWatchdogStillWaitsTenSecondsOnAnUntouchedStore() = runTest {
        var reports = 0

        OrchestratorModule.provideSabModeWatchdog(FakeSettingsRepository())
            .start(MutableStateFlow(DashboardMode.HU_MODE), backgroundScope) { reports++ }

        advanceTimeBy(9.seconds)
        runCurrent()
        assertEquals(0, reports)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, reports)
    }

    /** A hand-edited zero must not report a cluster that was not yet asked a second time. */
    @Test
    fun theWatchdogClampsAStoredWaitBelowTheRange() = runTest {
        val settings = FakeSettingsRepository(AppSettings(sabModeWaitSeconds = 0))
        var reports = 0

        OrchestratorModule.provideSabModeWatchdog(settings)
            .start(MutableStateFlow(DashboardMode.HU_MODE), backgroundScope) { reports++ }

        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(0, reports)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, reports)
    }

    /** One read per call: the owner decides when to ask, and what it gets is already clamped. */
    @Test
    fun aTunableReaderResolvesTheStoredValueEachTimeItIsAsked() = runTest {
        val settings = FakeSettingsRepository(AppSettings(weatherRefreshMinutes = 30))
        val interval = settings.reader(Tunable.WEATHER_REFRESH)

        assertEquals(30.minutes, interval())

        settings.updateSettings { it.copy(weatherRefreshMinutes = 999) }
        assertEquals(60.minutes, interval())
    }

    @Test
    fun theSentLingerIsClampedOnTheWayOut() = runTest {
        val settings = FakeSettingsRepository(AppSettings(replySentLingerSeconds = 999))

        val linger = MessageModule.provideSentLinger(settings, backgroundScope)
        runCurrent()

        assertEquals(10.seconds, linger.value)
    }
}
