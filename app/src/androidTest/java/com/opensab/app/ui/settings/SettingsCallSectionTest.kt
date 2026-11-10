package com.opensab.app.ui.settings

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opensab.app.R
import com.opensab.domain.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Only the calls switch: the call access row lives on the permissions screen, covered by
 * `PermissionStatusTest` and `PermissionsViewModelTest`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsCallSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theInterceptSwitchEmitsAnUpdate() {
        var emittedIntent: SettingsUiIntent? = null
        composeTestRule.setContent {
            SettingsScreen(
                state = SettingsUiState(settings = AppSettings(interceptIncomingCalls = false)),
                onIntent = { emittedIntent = it }
            )
        }

        val interceptTitle = context.getString(R.string.settings_intercept_calls_title)
        composeTestRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText(interceptTitle))

        composeTestRule
            .onNodeWithText(interceptTitle)
            .performClick()

        val update = emittedIntent as? SettingsUiIntent.UpdateSettings
        assertNotNull("Expected an UpdateSettings intent", update)
        val newSettings = update!!.transform(AppSettings(interceptIncomingCalls = false))
        assertEquals(true, newSettings.interceptIncomingCalls)
    }
}
