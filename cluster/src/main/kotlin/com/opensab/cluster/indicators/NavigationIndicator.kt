package com.opensab.cluster.indicators

import com.opensab.domain.nav.NavInstruction
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** NAVIGATION stays lit while guidance is running. */
class NavigationIndicator(
    private val currentInstruction: StateFlow<NavInstruction?>,
    private val publisher: ActiveIndicatorsPublisher
) : IndicatorTranslator {

    override val name: String = "navigation"

    override fun start(scope: CoroutineScope) {
        scope.launch { currentInstruction.collect { publisher.set(ActiveIndicator.NAVIGATION, it != null) } }
    }
}
