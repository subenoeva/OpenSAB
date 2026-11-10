package com.opensab.app.ui.sessions

import app.cash.turbine.test
import com.opensab.app.logging.JournalListing
import com.opensab.app.ui.MainDispatcherRule
import com.opensab.logging.journal.ExportRequest
import com.opensab.logging.journal.JournalReader
import com.opensab.logging.journal.JournalUsage
import com.opensab.logging.journal.SessionListing
import com.opensab.logging.journal.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val reader = FakeJournalReader(listOf(open("o"), ride("b"), ride("a")))

    private fun TestScope.viewModel() = mainDispatcherRule.register(SessionsViewModel(JournalListing(reader))).also { vm ->
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
    }

    @Test
    fun theListLoadsOnStart() = runTest {
        val vm = viewModel(); runCurrent()
        assertEquals(listOf("o", "b", "a"), vm.uiState.value.listings.map { it.baseName })
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun openingASessionShowsItsDetailAndBackClosesIt() = runTest {
        val vm = viewModel(); runCurrent()
        vm.sendIntent(SessionsUiIntent.Open("a")); runCurrent()
        assertEquals("a", vm.uiState.value.detail!!.baseName)
        vm.sendIntent(SessionsUiIntent.CloseDetail); runCurrent()
        assertNull(vm.uiState.value.detail)
    }

    @Test
    fun closingTheDetailRefreshesTheList() = runTest {
        val vm = viewModel(); runCurrent()
        vm.sendIntent(SessionsUiIntent.Open("a")); runCurrent()
        reader.listings = listOf(ride("c"), ride("b"), ride("a"))
        vm.sendIntent(SessionsUiIntent.CloseDetail); runCurrent()
        assertEquals(listOf("c", "b", "a"), vm.uiState.value.listings.map { it.baseName })
    }

    @Test
    fun sharingAsksForThePrivacyNoticeFirst() = runTest {
        val vm = viewModel(); runCurrent()
        vm.sendIntent(SessionsUiIntent.Open("a")); vm.sendIntent(SessionsUiIntent.ShareDetail); runCurrent()
        assertEquals(ExportRequest(listOf("a"), includeDays = false), vm.uiState.value.pendingExport)
        assertEquals(0, reader.exports.size)
    }

    @Test
    fun confirmingExportsAndSharesTheZip() = runTest {
        val vm = viewModel(); runCurrent()
        vm.sendIntent(SessionsUiIntent.Open("a")); vm.sendIntent(SessionsUiIntent.ShareDetail); runCurrent()
        vm.uiEvents.test {
            vm.sendIntent(SessionsUiIntent.ConfirmExport); runCurrent()
            assertEquals(SessionsUiEvent.Share(reader.zip), awaitItem())
        }
        assertNull(vm.uiState.value.pendingExport)
    }

    @Test
    fun cancellingTheNoticeExportsNothing() = runTest {
        val vm = viewModel(); runCurrent()
        vm.sendIntent(SessionsUiIntent.ShareAll); vm.sendIntent(SessionsUiIntent.CancelExport); runCurrent()
        assertNull(vm.uiState.value.pendingExport)
        assertEquals(0, reader.exports.size)
    }

    @Test
    fun shareAllIncludesTheDaysAndEveryClosedSession() = runTest {
        val vm = viewModel(); runCurrent()
        vm.sendIntent(SessionsUiIntent.ShareAll); runCurrent()
        assertEquals(ExportRequest(listOf("b", "a"), includeDays = true), vm.uiState.value.pendingExport) // "o" is open
    }

    @Test
    fun aFailedExportIsReported() = runTest {
        reader.exportResult = Result.failure(IOException("disk"))
        val vm = viewModel(); runCurrent()
        vm.sendIntent(SessionsUiIntent.ShareAll); runCurrent()
        vm.uiEvents.test {
            vm.sendIntent(SessionsUiIntent.ConfirmExport); runCurrent()
            assertEquals(SessionsUiEvent.ExportFailed, awaitItem())
        }
    }

    @Test
    fun longPressSelectionSharesTheSelected() = runTest {
        val vm = viewModel(); runCurrent()
        vm.sendIntent(SessionsUiIntent.ToggleSelected("a")); vm.sendIntent(SessionsUiIntent.ToggleSelected("b")); runCurrent()
        vm.sendIntent(SessionsUiIntent.ShareSelected); runCurrent()
        assertEquals(ExportRequest(listOf("a", "b"), includeDays = false), vm.uiState.value.pendingExport)
        assertEquals(emptySet<String>(), vm.uiState.value.selected)
    }

    @Test
    fun theFilterAndTheAttemptsFoldAreState() = runTest {
        val vm = viewModel(); runCurrent()
        vm.sendIntent(SessionsUiIntent.SetOnlyAttention(true)); vm.sendIntent(SessionsUiIntent.ToggleAttempts("k")); runCurrent()
        assertEquals(true, vm.uiState.value.onlyAttention)
        assertEquals(setOf("k"), vm.uiState.value.expandedAttempts)
        vm.sendIntent(SessionsUiIntent.ToggleAttempts("k")); runCurrent()
        assertEquals(emptySet<String>(), vm.uiState.value.expandedAttempts)
    }

    @Test
    fun backIsAnEvent() = runTest {
        val vm = viewModel(); runCurrent()
        vm.uiEvents.test {
            vm.sendIntent(SessionsUiIntent.NavigateBack); runCurrent()
            assertEquals(SessionsUiEvent.NavigateBack, awaitItem())
        }
    }

    private fun open(name: String) = SessionListing(name, summary = null, isOpen = true, sizeBytes = 0)

    private fun ride(name: String) = SessionListing(
        baseName = name,
        summary = SessionSummary(summarizerVersion = 1, baseName = name, connected = true),
        isOpen = false,
        sizeBytes = 100,
    )

    /** Answers at once, on the caller's thread; the real journal answers later, from its executor. */
    private class FakeJournalReader(var listings: List<SessionListing>) : JournalReader {
        val zip = File("exports/sessions.zip")
        var exportResult: Result<File> = Result.success(zip)
        val exports = mutableListOf<ExportRequest>()

        override val usage = JournalUsage(0, 0)

        override fun setUsageListener(listener: ((JournalUsage) -> Unit)?) = Unit

        override fun listSessions(callback: (List<SessionListing>) -> Unit) = callback(listings)

        override fun export(request: ExportRequest, callback: (Result<File>) -> Unit) {
            exports += request
            callback(exportResult)
        }
    }
}
