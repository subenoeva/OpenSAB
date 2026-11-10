package com.opensab.app.di

import com.opensab.domain.settings.SettingsRepository
import com.opensab.domain.settings.Tunable
import kotlin.time.Duration

/**
 * What the graph hands the one owner of a [Tunable]: a read of the store, clamped on the way out.
 * When it is asked — once per session, once per request — is the owner's decision, stated in its
 * own KDoc.
 */
internal fun SettingsRepository.reader(tunable: Tunable): suspend () -> Duration =
    { tunable.resolve(getSettings()) }
