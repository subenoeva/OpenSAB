package com.opensab.app.ui.dashboard.mirror

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import com.opensab.app.R
import com.opensab.cluster.mirror.MirrorActionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MirrorActionUiTest {

    @Test
    fun everyTokenDeclaresAnActionUi() {
        for (token in MirrorActionToken.entries) {
            val ui = token.ui()
            assertNotEquals("MirrorActionToken.$token has no label resource", 0, ui.labelRes)
        }
    }

    @Test
    fun shouldMapPlaceCallToCallLabelAndIcon() {
        val ui = MirrorActionToken.PLACE_CALL.ui()
        assertEquals(R.string.mirror_action_place_call, ui.labelRes)
        assertEquals(Icons.Filled.Call, ui.icon)
    }

    @Test
    fun shouldMapRetryCallToRetryLabelAndIcon() {
        val ui = MirrorActionToken.RETRY_CALL.ui()
        assertEquals(R.string.mirror_action_retry_call, ui.labelRes)
        assertEquals(Icons.Filled.Replay, ui.icon)
    }

    @Test
    fun shouldMapRefreshWeatherToRefreshLabelAndIcon() {
        val ui = MirrorActionToken.REFRESH_WEATHER.ui()
        assertEquals(R.string.mirror_action_refresh_weather, ui.labelRes)
        assertEquals(Icons.Filled.Refresh, ui.icon)
    }
}
