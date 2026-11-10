package com.opensab.app.ui.base

import android.util.Log
import com.opensab.app.ui.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import timber.log.Timber

/**
 * An intent the buffer refuses is a rider's press that does nothing, so it must at least leave a
 * line in the log, the same as a dropped UI event does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BaseViewModelIntentOverflowTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class ErrorTree : Timber.Tree() {
        val errors = mutableListOf<String>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == Log.ERROR) errors += message
        }
    }

    private val tree = ErrorTree()

    @Before
    fun plant() = Timber.plant(tree)

    @After
    fun uproot() = Timber.uproot(tree)

    private class StuckViewModel : BaseViewModel<Int, String, Nothing>(0) {
        val release = CompletableDeferred<Unit>()
        override suspend fun handleIntent(intent: String) {
            release.await()
        }
    }

    @Test
    fun anIntentRefusedByAFullBufferIsLoggedAsAnError() {
        val viewModel = mainDispatcherRule.register(StuckViewModel())

        viewModel.sendIntent("in-flight")
        repeat(INTENT_BUFFER) { viewModel.sendIntent("queued-$it") }
        assertEquals("nothing is lost while the buffer has room", emptyList<String>(), tree.errors)

        viewModel.sendIntent("overflow")

        assertEquals(1, tree.errors.size)
        assertTrue(tree.errors.single(), "overflow" in tree.errors.single())
        viewModel.release.complete(Unit)
    }

    private companion object {
        const val INTENT_BUFFER = 64
    }
}
