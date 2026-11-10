package com.opensab.app.ui.base

import com.opensab.app.ui.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The app once died forgetting a motorcycle: the key store threw and nothing between
 * `handleIntent` and `viewModelScope` caught it. A failing intent has to leave the ViewModel
 * standing and still answering the next one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BaseViewModelIntentFailureTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class CountingViewModel : BaseViewModel<Int, String, Nothing>(0) {
        override suspend fun handleIntent(intent: String) {
            if (intent == FAILING_INTENT) error("the store would not answer")
            updateState { this + 1 }
        }

        companion object {
            const val FAILING_INTENT = "boom"
        }
    }

    @Test
    fun anIntentThatThrowsIsSurvivedAndTheNextOneIsStillHandled() = runTest {
        val viewModel = mainDispatcherRule.register(CountingViewModel())

        viewModel.sendIntent("count")
        viewModel.sendIntent(CountingViewModel.FAILING_INTENT)
        viewModel.sendIntent("count")

        assertEquals(
            "The collector must outlive a failed intent, or the screen goes deaf after one error",
            2,
            viewModel.uiState.value
        )
    }
}
