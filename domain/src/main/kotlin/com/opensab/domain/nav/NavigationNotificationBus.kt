package com.opensab.domain.nav

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The pipe from `OpenSabNotificationListenerService` (`:app`) to [NavigationManager]. Built only by
 * `NotificationModule.provideNavigationNotificationBus`. No replay: an instruction is an event, not
 * a state.
 */
class NavigationNotificationBus {

    private val _instructions = MutableSharedFlow<NavInstruction?>(extraBufferCapacity = 64)
    val instructions: SharedFlow<NavInstruction?> = _instructions.asSharedFlow()

    fun post(instruction: NavInstruction?) {
        _instructions.tryEmit(instruction)
    }
}
