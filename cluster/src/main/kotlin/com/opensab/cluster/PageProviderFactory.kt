package com.opensab.cluster

import com.opensab.cluster.call.CallMenuPageProvider
import com.opensab.cluster.call.CallPageProvider
import com.opensab.cluster.message.MessagePageProvider
import com.opensab.cluster.nav.NavMenuPageProvider
import com.opensab.cluster.nav.NavigationPageProvider
import com.opensab.cluster.rootmenu.RootMenuPageProvider
import com.opensab.cluster.text.ClusterPresentation
import com.opensab.cluster.weather.WeatherPageProvider

/** Creates the cluster pages and decides which instances are retained for the session. */
interface PageProviderFactory {
    val presentation: ClusterPresentation

    // Engine entry points
    fun createRootMenuPage(): RootMenuPageProvider
    fun createInterruptSource(): InterruptSource

    // Identity contracts (session-scoped / retained)
    fun callPage(): CallPageProvider
    fun callMenuPage(): CallMenuPageProvider
    fun createWeatherPage(): WeatherPageProvider
    fun messagePage(): MessagePageProvider
    fun liveNavPage(): NavigationPageProvider
    fun navMenuPage(): NavMenuPageProvider
}
