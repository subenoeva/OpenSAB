package com.opensab.domain.nav

/**
 * When a voice-launched route is sent to the navigation app again, and what counts as it having
 * started.
 *
 * Google Maps drops a `google.navigation:` intent that cold-starts it behind the keyguard, and
 * unlocking does not replay it; the same intent sent once Maps is up starts guidance. So the launch
 * is re-sent on each unlock, at most [MAX_RELAUNCHES] times.
 *
 * Guidance is the first instruction that is not [baseline], the one held when the route was
 * launched: compared by identity, since every notification the listener reads is a new object.
 *
 * **Owner: the one voice search that launched the route**, and it lives as long as that search.
 */
data class NavRelaunchPolicy(
    private val baseline: NavInstruction?,
    private val relaunches: Int = 0
) {
    val mayRelaunch: Boolean get() = relaunches < MAX_RELAUNCHES

    fun relaunched(): NavRelaunchPolicy = copy(relaunches = relaunches + 1)

    fun startsGuidance(instruction: NavInstruction?): Boolean = instruction != null && instruction !== baseline

    companion object {
        const val MAX_RELAUNCHES: Int = 2
    }
}
