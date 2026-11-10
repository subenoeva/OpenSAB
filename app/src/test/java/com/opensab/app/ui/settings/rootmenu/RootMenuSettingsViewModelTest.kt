package com.opensab.app.ui.settings.rootmenu

import app.cash.turbine.test
import com.opensab.app.ui.MainDispatcherRule
import com.opensab.domain.settings.FakeSettingsRepository
import com.opensab.domain.settings.RootMenuEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootMenuSettingsViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeSettingsRepository()
    private fun viewModel() = RootMenuSettingsViewModel(repository)

    @Test
    fun `starts from the stored layout`() = runTest {
        repository.updateSettings { it.copy(rootMenuOrder = listOf("MUSIC"), rootMenuHidden = setOf("VOLUME")) }

        viewModel().uiState.test {
            val rows = expectMostRecentItem().rows
            assertEquals(RootMenuEntry.MUSIC, rows.first().entry)
            assertFalse(rows.single { it.entry == RootMenuEntry.VOLUME }.visible)
        }
    }

    @Test
    fun `a drop writes the new order`() = runTest {
        val vm = viewModel()
        vm.sendIntent(RootMenuSettingsUiIntent.Move(from = 4, to = 0))

        assertEquals("MUSIC", repository.getSettings().rootMenuOrder.first())
    }

    @Test
    fun `a toggle writes the hidden set`() = runTest {
        val vm = viewModel()
        vm.sendIntent(RootMenuSettingsUiIntent.Toggle(RootMenuEntry.VOLUME))

        assertEquals(setOf("VOLUME"), repository.getSettings().rootMenuHidden)
    }

    /** The carousel is never empty: the last visible entry refuses, and nothing is written. */
    @Test
    fun `hiding the last visible entry writes nothing`() = runTest {
        val onlyMusic = RootMenuEntry.entries.filter { it != RootMenuEntry.MUSIC }.map { it.name }.toSet()
        repository.updateSettings { it.copy(rootMenuHidden = onlyMusic) }
        val before = repository.getSettings()

        viewModel().sendIntent(RootMenuSettingsUiIntent.Toggle(RootMenuEntry.MUSIC))

        assertEquals(before, repository.getSettings())
    }

    @Test
    fun `reset goes back to the factory layout`() = runTest {
        repository.updateSettings { it.copy(rootMenuOrder = listOf("MUSIC"), rootMenuHidden = setOf("CALL")) }

        viewModel().sendIntent(RootMenuSettingsUiIntent.Reset)

        assertTrue(repository.getSettings().rootMenuOrder.isEmpty())
        assertTrue(repository.getSettings().rootMenuHidden.isEmpty())
    }

    @Test
    fun `back asks the screen to leave`() = runTest {
        val vm = viewModel()
        vm.uiEvents.test {
            vm.sendIntent(RootMenuSettingsUiIntent.NavigateBack)
            assertEquals(RootMenuSettingsUiEvent.NavigateBack, awaitItem())
        }
    }
}
