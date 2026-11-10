package com.opensab.domain.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SettingsRepository], shared by every test that has to drive a preference. */
class FakeSettingsRepository(
    initial: AppSettings = AppSettings()
) : SettingsRepository {

    private val state = MutableStateFlow(initial)

    /**
     * How many one-shot reads this repository has served.
     *
     * A decision taken once per process is a different thing from a preference that is observed,
     * and the difference only shows as a count: `AppStartViewModel` has to read the intro flag
     * once and never move the rider again, however many times the flow emits afterwards.
     */
    var readCount: Int = 0
        private set

    /** How many writes landed. `Continue` pressed twice still has to write once. */
    var updateCount: Int = 0
        private set

    override val settingsFlow: Flow<AppSettings> = state

    override suspend fun getSettings(): AppSettings {
        readCount++
        return state.value
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        updateCount++
        state.value = transform(state.value)
    }

    override suspend fun resetToDefaults() {
        state.value = AppSettings()
    }

    /** Flips a preference without going through a coroutine, for a test that just needs it set. */
    fun set(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
