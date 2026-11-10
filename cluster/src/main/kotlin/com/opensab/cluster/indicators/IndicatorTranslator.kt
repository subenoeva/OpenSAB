package com.opensab.cluster.indicators

import kotlinx.coroutines.CoroutineScope

/**
 * Turns one source's state into its bits of ACTIVE_INDICATORS. Reads no preference: ignoring a call
 * or a message is not hiding it, so the bit is lit whatever the inbound mode.
 *
 * One translator per capability, started by the `indicators` session participant after the sources.
 */
interface IndicatorTranslator {
    val name: String

    /** Launches one collector into [scope]; it dies with the session. No stop: `forget()` zeroes the mask. */
    fun start(scope: CoroutineScope)
}
