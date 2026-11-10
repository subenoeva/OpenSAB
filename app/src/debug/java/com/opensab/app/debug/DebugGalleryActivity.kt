package com.opensab.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.opensab.app.theme.OpenSABTheme
import com.opensab.app.ui.common.BodyStyleIconGrid
import com.opensab.app.ui.preview.PreviewMockData
import com.opensab.app.ui.scan.ClusterQuestionDialog
import com.opensab.app.ui.settings.VehicleProfileDialog

/**
 * Shows one composable on a real device, for the screens a Preview cannot put on an emulator.
 * Picked by the `screen` extra: `icons`, `cluster_question` or `profile_dialog`.
 */
class DebugGalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val screen = intent.getStringExtra(EXTRA_SCREEN) ?: SCREEN_ICONS
        setContent {
            OpenSABTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        SCREEN_ICONS -> BodyStyleIconGrid(modifier = Modifier.safeDrawingPadding())
                        SCREEN_CLUSTER_QUESTION -> ClusterQuestionDialog(onAnswer = {}, onDismiss = {})
                        SCREEN_PROFILE_DIALOG -> VehicleProfileDialog(
                            vehicle = PreviewMockData.sampleVehicle1,
                            onSave = {},
                            onDismiss = {}
                        )
                        else -> Text("Unknown screen: $screen", modifier = Modifier.safeDrawingPadding())
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_ICONS = "icons"
        const val SCREEN_CLUSTER_QUESTION = "cluster_question"
        const val SCREEN_PROFILE_DIALOG = "profile_dialog"
    }
}
