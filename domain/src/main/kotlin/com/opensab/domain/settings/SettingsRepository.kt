package com.opensab.domain.settings

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {

    val settingsFlow: Flow<AppSettings>

    suspend fun getSettings(): AppSettings

    /** Atomic read-modify-write. */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)

    suspend fun resetToDefaults()
}
