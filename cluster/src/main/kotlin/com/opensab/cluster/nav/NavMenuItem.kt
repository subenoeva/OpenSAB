package com.opensab.cluster.nav

import com.opensab.cluster.text.ClusterToken
import com.opensab.sab.protocol.projection.IconId

/** Items of the navigation menu shown while no guidance is running. */
internal enum class NavMenuItem(val title: ClusterToken, val iconId: IconId) {
    VOICE_SEARCH(ClusterToken.NAV_MENU_VOICE_SEARCH, IconId.MIC)
}
