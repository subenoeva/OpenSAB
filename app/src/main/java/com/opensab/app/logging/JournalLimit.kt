package com.opensab.app.logging

import com.opensab.domain.settings.SessionLogLimit
import com.opensab.domain.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owner of the limit the journal reads. Its collector is the only writer of [bytes] and of
 * [loaded]; the journal reads them from its own executor, hence the volatiles.
 */
@Singleton
class JournalLimit @Inject constructor(settings: SettingsRepository, scope: CoroutineScope) {

    /** The rider's limit, or the default until the settings are read. For display. */
    @Volatile
    var bytes: Long = SessionLogLimit.bytes(SessionLogLimit.DEFAULT_MB)
        private set

    @Volatile
    private var loaded: Boolean = false

    /**
     * What the journal trims to. Unbounded until the settings have been read, so a startup sweep
     * that runs first cannot delete down to the default a rider who chose 200 MB never asked for.
     */
    val journalBytes: Long
        get() = if (loaded) bytes else Long.MAX_VALUE

    init {
        scope.launch {
            settings.settingsFlow
                .map { SessionLogLimit.bytes(it.sessionLogLimitMb) }
                .distinctUntilChanged()
                .collect {
                    bytes = it
                    loaded = true
                }
        }
    }
}
