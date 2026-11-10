package com.opensab.domain.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.opensab.logging.Log

interface NavigationManager {

    val currentInstruction: StateFlow<NavInstruction?>

    /**
     * Starts the one thing this adapter owns: the instruction flow that the navigation page reads
     * and that the cluster lights the NAVIGATION bit from (`NavigationIndicator`).
     *
     * It writes no frames. Guidance reaches the cluster as the page the rider mounted, redrawn
     * through `PageProvider.invalidations` — the base screen has one owner, and it is the engine.
     */
    fun start(scope: CoroutineScope)
    fun stop()

    fun updateInstruction(instruction: NavInstruction?)
    fun repeatManeuver()
}

/** @param notifications one instance per graph, built by `NotificationModule`. */
class DefaultNavigationManager(
    private val notifications: NavigationNotificationBus
) : NavigationManager {

    private val _currentInstruction = MutableStateFlow<NavInstruction?>(null)
    override val currentInstruction: StateFlow<NavInstruction?> = _currentInstruction.asStateFlow()

    private var busJob: Job? = null

    override fun start(scope: CoroutineScope) {
        Log.i("Starting NavigationManager service")

        busJob?.cancel()
        busJob = scope.launch {
            notifications.instructions.collect { instruction ->
                updateInstruction(instruction)
            }
        }
    }

    override fun stop() {
        busJob?.cancel()
        busJob = null
        _currentInstruction.value = null
    }

    override fun updateInstruction(instruction: NavInstruction?) {
        _currentInstruction.value = instruction
        Log.d("Navigation instruction updated: $instruction")
    }

    override fun repeatManeuver() {
        Log.i("Repeating navigation maneuver: %s", _currentInstruction.value)
    }
}
