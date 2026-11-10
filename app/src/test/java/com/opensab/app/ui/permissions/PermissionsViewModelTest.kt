package com.opensab.app.ui.permissions

import app.cash.turbine.test
import com.opensab.app.ui.MainDispatcherRule
import com.opensab.domain.settings.FakeSettingsRepository
import com.opensab.domain.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val entries = PermissionCatalog.entriesFor(sdkInt = 36)

    private fun createViewModel(
        isIntro: Boolean = false,
        entries: List<PermissionEntry> = this.entries,
        statusReader: PermissionStatusReader = FakePermissionStatusReader(),
        settingsRepository: SettingsRepository = FakeSettingsRepository()
    ): PermissionsViewModel = mainDispatcherRule.register(
        PermissionsViewModel(
            isIntro = isIntro,
            entries = entries,
            statusReader = statusReader,
            settingsRepository = settingsRepository
        )
    )

    @Test
    fun `should list every row of the catalog when it is built`() = runTest {
        val viewModel = createViewModel()

        assertEquals(entries, viewModel.uiState.value.rows.map { it.entry })
    }

    @Test
    fun `should read the status of every row from the reader`() = runTest {
        val reader = FakePermissionStatusReader(
            statuses = mapOf(
                PermissionId.BLUETOOTH to PermissionStatus.GRANTED,
                PermissionId.ANSWER_CALLS to PermissionStatus.UNSUPPORTED
            ),
            fallback = PermissionStatus.DENIED
        )
        val viewModel = createViewModel(statusReader = reader)

        val rows = viewModel.uiState.value.rows.associate { it.entry.id to it.status }

        assertEquals(PermissionStatus.GRANTED, rows[PermissionId.BLUETOOTH])
        assertEquals(PermissionStatus.UNSUPPORTED, rows[PermissionId.ANSWER_CALLS])
        assertEquals(PermissionStatus.DENIED, rows[PermissionId.MICROPHONE])
    }

    @Test
    fun `should request a runtime permission with the permissions of its entry`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiEvents.test {
            viewModel.sendIntent(PermissionsUiIntent.Grant(PermissionId.MICROPHONE))

            assertEquals(
                PermissionsUiEvent.RequestRuntime(listOf("android.permission.RECORD_AUDIO")),
                awaitItem()
            )
        }
    }

    @Test
    fun `should open a system screen for a special access`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiEvents.test {
            viewModel.sendIntent(PermissionsUiIntent.Grant(PermissionId.NOTIFICATION_ACCESS))

            assertEquals(
                PermissionsUiEvent.OpenSystemScreen(PermissionId.NOTIFICATION_ACCESS),
                awaitItem()
            )
        }
    }

    @Test
    fun `should open a system screen for overlay permission`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiEvents.test {
            viewModel.sendIntent(PermissionsUiIntent.Grant(PermissionId.DRAW_OVERLAYS))

            assertEquals(
                PermissionsUiEvent.OpenSystemScreen(PermissionId.DRAW_OVERLAYS),
                awaitItem()
            )
        }
    }

    @Test
    fun `should open a system screen for a system toggle`() = runTest {
        val viewModel = createViewModel()

        viewModel.uiEvents.test {
            viewModel.sendIntent(PermissionsUiIntent.Grant(PermissionId.BLUETOOTH_ENABLED))

            assertEquals(
                PermissionsUiEvent.OpenSystemScreen(PermissionId.BLUETOOTH_ENABLED),
                awaitItem()
            )
        }
    }

    /**
     * A row this phone cannot grant is painted and is not clickable, but the ViewModel does not take
     * the composable's word for that: an unsupported row that is tapped anyway opens nothing.
     */
    @Test
    fun `should stay silent when an unsupported row is tapped`() = runTest {
        val reader = FakePermissionStatusReader(
            statuses = mapOf(PermissionId.ANSWER_CALLS to PermissionStatus.UNSUPPORTED)
        )
        val viewModel = createViewModel(statusReader = reader)

        viewModel.uiEvents.test {
            viewModel.sendIntent(PermissionsUiIntent.Grant(PermissionId.ANSWER_CALLS))

            expectNoEvents()
        }
    }

    @Test
    fun `should recompose the rows on refresh`() = runTest {
        val reader = FakePermissionStatusReader(fallback = PermissionStatus.DENIED)
        val viewModel = createViewModel(statusReader = reader)
        assertTrue(viewModel.uiState.value.rows.all { it.status == PermissionStatus.DENIED })

        reader.fallback = PermissionStatus.GRANTED
        viewModel.sendIntent(PermissionsUiIntent.Refresh)

        assertTrue(viewModel.uiState.value.rows.all { it.status == PermissionStatus.GRANTED })
    }

    /**
     * The flag belongs to this ViewModel and it is written once, on the press. A second `Continue`
     * — a double tap, a replayed intent — must not turn into a second write.
     */
    @Test
    fun `should write the intro flag once when the rider continues`() = runTest {
        val repository = FakeSettingsRepository()
        val viewModel = createViewModel(isIntro = true, settingsRepository = repository)

        viewModel.sendIntent(PermissionsUiIntent.Continue)
        viewModel.sendIntent(PermissionsUiIntent.Continue)

        assertTrue(repository.getSettings().permissionsIntroSeen)
        assertEquals(1, repository.updateCount)
    }

    @Test
    fun `should go to the scan screen when the rider continues`() = runTest {
        val viewModel = createViewModel(isIntro = true)

        viewModel.uiEvents.test {
            viewModel.sendIntent(PermissionsUiIntent.Continue)

            assertEquals(PermissionsUiEvent.NavigateToScan, awaitItem())
        }
    }

    /** There is no footer button to emit it from Settings — and the ViewModel does not rely on that. */
    @Test
    fun `should not write the intro flag when it was opened from settings`() = runTest {
        val repository = FakeSettingsRepository()
        val viewModel = createViewModel(isIntro = false, settingsRepository = repository)

        viewModel.sendIntent(PermissionsUiIntent.Continue)

        assertEquals(0, repository.updateCount)
        assertFalse(repository.getSettings().permissionsIntroSeen)
    }

    @Test
    fun `should ask to go back when it was opened from settings`() = runTest {
        val viewModel = createViewModel(isIntro = false)

        viewModel.uiEvents.test {
            viewModel.sendIntent(PermissionsUiIntent.NavigateBack)

            assertEquals(PermissionsUiEvent.NavigateBack, awaitItem())
        }
    }

    /**
     * The footer cannot flicker from "back arrow" to "Continue" on a first run, so `isIntro` has to
     * be right in the **first** state emitted, which is why it rides in the route and arrives by
     * assisted injection.
     */
    @Test
    fun `should start on the footer the route asked for`() = runTest {
        assertTrue(createViewModel(isIntro = true).uiState.value.isIntro)
        assertFalse(createViewModel(isIntro = false).uiState.value.isIntro)
    }
}
