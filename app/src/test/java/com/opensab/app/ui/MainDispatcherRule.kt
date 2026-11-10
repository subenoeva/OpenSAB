package com.opensab.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps the Main dispatcher for a test one, and tears down the ViewModels built under it.
 *
 * Every ViewModel here builds on `viewModelScope`, which dispatches on `Dispatchers.Main.immediate`
 * and throws on a plain JVM test. An unconfined dispatcher also makes the collectors started in
 * `init` subscribe before the constructor returns, which is what lets a test send an intent on the
 * very next line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {

    private val viewModelStore = ViewModelStore()

    /**
     * Hands [viewModel] to the rule so its `viewModelScope` is cancelled when the test ends.
     *
     * `ViewModel.clear()` is the only thing that cancels that scope and it is not public, so a
     * [ViewModelStore] is the supported way to reach it. Without this the collectors started in
     * `init` — and anything the connection pipeline launched on the same scope — outlive the test
     * and go on dispatching on a Main that [resetMain] has already torn down, which surfaces as an
     * unrelated failure in whichever test class runs next in the same JVM.
     */
    fun <T : ViewModel> register(viewModel: T): T = viewModel.also {
        viewModelStore.put(it.toString(), it)
    }

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        // Cleared before the dispatcher goes away: cancelling a scope still needs a live Main.
        viewModelStore.clear()
        Dispatchers.resetMain()
    }
}
