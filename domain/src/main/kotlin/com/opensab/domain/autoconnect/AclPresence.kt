package com.opensab.domain.autoconnect

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Which devices the system has reported as linked, by address, from the ACL broadcasts alone.
 *
 * It is what the phone was told, not what is true: nothing is linked until a broadcast says so, so
 * a process started for any other reason knows of no motorcycle, and a link that drops without an
 * `ACL_DISCONNECTED` stays linked here until the next one. Both errors are safe for the one reader,
 * the retry of a failed connection: the first retries nothing, the second retries a bounded number
 * of times.
 *
 * **Written only by the ACL broadcast handler**, once per broadcast. In memory: it dies with the
 * process, and so does the retry that reads it.
 */
class AclPresence {

    private val linkedAddresses = MutableStateFlow<Set<String>>(emptySet())

    fun record(action: String, deviceAddress: String) {
        val address = deviceAddress.uppercase()
        when (action) {
            AutoConnectEvaluator.ACTION_ACL_CONNECTED -> linkedAddresses.update { it + address }
            AutoConnectEvaluator.ACTION_ACL_DISCONNECTED -> linkedAddresses.update { it - address }
        }
    }

    /** Emits the current answer at once and every change after it. */
    fun linked(deviceAddress: String): Flow<Boolean> {
        val address = deviceAddress.uppercase()
        return linkedAddresses.map { address in it }.distinctUntilChanged()
    }
}
