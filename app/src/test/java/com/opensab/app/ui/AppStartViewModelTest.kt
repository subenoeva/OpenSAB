package com.opensab.app.ui

import com.opensab.app.ui.navigation.AppRoute
import com.opensab.domain.settings.AppSettings
import com.opensab.domain.settings.FakeSettingsRepository
import com.opensab.domain.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Reading DataStore is asynchronous, so the initial answer is "I do not know yet": composing
 * `NavDisplay` with `Scan` and correcting afterwards would flash the scan screen on every first run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppStartViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun createViewModel(repository: SettingsRepository) =
        mainDispatcherRule.register(AppStartViewModel(repository))

    /**
     * A repository whose one-shot read hangs until it is let go — which is what reading DataStore
     * looks like from here, and what the in-memory double cannot show: on an unconfined dispatcher
     * a fake answers before the constructor has returned, so it would prove the opposite of this.
     */
    private class GatedSettingsRepository(
        private val settings: AppSettings = AppSettings()
    ) : SettingsRepository {

        private val gate = CompletableDeferred<Unit>()

        override val settingsFlow: Flow<AppSettings> = MutableStateFlow(settings)

        override suspend fun getSettings(): AppSettings {
            gate.await()
            return settings
        }

        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = Unit

        override suspend fun resetToDefaults() = Unit

        fun release() {
            gate.complete(Unit)
        }
    }

    @Test
    fun `should not decide a route before the flag is read`() = runTest {
        val repository = GatedSettingsRepository()
        val viewModel = createViewModel(repository)
        runCurrent()

        assertNull("a route guessed before the flag is read is a route that flashes", viewModel.startRoute.value)

        repository.release()
        runCurrent()

        assertEquals(AppRoute.Permissions(isIntro = true), viewModel.startRoute.value)
    }

    @Test
    fun `should open the permissions intro on a first launch`() = runTest {
        val repository = FakeSettingsRepository(AppSettings(permissionsIntroSeen = false))
        val viewModel = createViewModel(repository)
        runCurrent()

        assertEquals(AppRoute.Permissions(isIntro = true), viewModel.startRoute.value)
    }

    /** It opens itself once, whatever is still denied. */
    @Test
    fun `should go straight to the scan screen once the intro has been seen`() = runTest {
        val repository = FakeSettingsRepository(AppSettings(permissionsIntroSeen = true))
        val viewModel = createViewModel(repository)
        runCurrent()

        assertEquals(AppRoute.Scan, viewModel.startRoute.value)
    }

    @Test
    fun `should read the flag once per process`() = runTest {
        val repository = FakeSettingsRepository(AppSettings(permissionsIntroSeen = true))
        createViewModel(repository)
        runCurrent()

        assertEquals(1, repository.readCount)
    }

    /**
     * The flag is captured, not observed: pressing "Continue" writes it, and that write must not
     * move the rider off the screen he is standing on.
     */
    @Test
    fun `should not move the rider when the flag changes mid session`() = runTest {
        val repository = FakeSettingsRepository(AppSettings(permissionsIntroSeen = false))
        val viewModel = createViewModel(repository)
        runCurrent()
        assertEquals(AppRoute.Permissions(isIntro = true), viewModel.startRoute.value)

        repository.set { it.copy(permissionsIntroSeen = true) }
        runCurrent()

        assertEquals(AppRoute.Permissions(isIntro = true), viewModel.startRoute.value)
    }
}
