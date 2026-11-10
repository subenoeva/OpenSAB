package com.opensab.app.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * MVI base: state as a `StateFlow`, intents handled one at a time on the ViewModel's scope, and
 * one-off events through a buffered channel that queues them while no one collects.
 */
abstract class BaseViewModel<UiState, UiIntent, UiEvent>(initialState: UiState) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState)

    val uiState = _uiState.asStateFlow()

    private val _uiEvents = Channel<UiEvent>(Channel.BUFFERED)

    val uiEvents = _uiEvents.receiveAsFlow()

    protected fun updateState(transform: UiState.() -> UiState) {
        _uiState.update {
            transform(it)
        }
    }

    /**
     * Bounded [Channel.BUFFERED] rather than [Channel.UNLIMITED]: a ViewModel whose screen is never
     * collected (a route under the back stack, the app in the background) would otherwise grow the
     * queue for as long as it lives and replay the whole backlog when the screen came back.
     *
     * A refused send is logged as an error, never swallowed: a dropped `NavigateToDashboard` leaves
     * the rider on the scan screen with a live session, and a full buffer means a runaway emitter or
     * a screen that never collects.
     */
    protected fun sendEvent(event: UiEvent) {
        val result = _uiEvents.trySend(event)
        if (!result.isSuccess) {
            Timber.e("Dropped UI event %s (channel closed: %b)", event, result.isClosed)
        }
    }

    private val uiIntents = MutableSharedFlow<UiIntent>(extraBufferCapacity = 64)

    /**
     * One failed intent must take down neither the app nor the loop. An exception escaping
     * [handleIntent] on `viewModelScope` reaches no handler and kills the process: that is how an
     * unreadable key store turned forgetting a motorcycle into a crash. The failure is logged and
     * the collector stays alive; cancellation is rethrown untouched.
     */
    init {
        viewModelScope.launch {
            uiIntents.collect { intent ->
                try {
                    handleIntent(intent)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Timber.e(failure, "Intent %s failed", intent)
                }
            }
        }
    }

    /** A refused intent is logged like a refused event: a press that does nothing leaves a trace. */
    fun sendIntent(intent: UiIntent) {
        if (!uiIntents.tryEmit(intent)) {
            Timber.e("Dropped UI intent %s: buffer full", intent)
        }
    }

    protected abstract suspend fun handleIntent(intent: UiIntent)

    protected val currentState: UiState
        get() = _uiState.value

    /** Collects this flow only while [uiState] has subscribers, like `WhileSubscribed`. */
    @OptIn(ExperimentalCoroutinesApi::class)
    protected fun <T> Flow<T>.whileSubscribed(): Flow<T> = _uiState.subscriptionCount
        .map { it > 0 }
        .distinctUntilChanged()
        .flatMapLatest { active -> if (active) this@whileSubscribed else emptyFlow() }
}
