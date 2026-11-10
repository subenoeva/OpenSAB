package com.opensab.app.logging

import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JournalLimitTest {

    @Test
    fun theLimitFollowsTheSetting() = runTest {
        val settings = FakeSettingsRepository()
        val limit = JournalLimit(settings, backgroundScope)
        runCurrent()
        assertEquals(50L shl 20, limit.bytes)
        settings.updateSettings { it.copy(sessionLogLimitMb = 200) }
        runCurrent()
        assertEquals(200L shl 20, limit.bytes)
    }

    @Test
    fun theJournalDeletesNothingUntilTheSettingHasBeenRead() = runTest {
        val settings = FakeSettingsRepository(AppSettings(sessionLogLimitMb = 25))
        val limit = JournalLimit(settings, backgroundScope)
        assertEquals(Long.MAX_VALUE, limit.journalBytes)
        runCurrent()
        assertEquals(25L shl 20, limit.journalBytes)
    }
}
