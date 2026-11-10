package com.opensab.cluster.indicators

import com.opensab.domain.call.CallInfo
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * PHONE stays lit while telephony holds a live call, whether it is still ringing or already
 * answered. The engine reads the very same flow to decide
 * whether the call page holds the screen, so the bit and the page can never disagree.
 */
class CallIndicator(
    private val activeCall: StateFlow<CallInfo?>,
    private val publisher: ActiveIndicatorsPublisher
) : IndicatorTranslator {

    override val name: String = "call"

    override fun start(scope: CoroutineScope) {
        scope.launch { activeCall.collect { publisher.set(ActiveIndicator.PHONE, it != null) } }
    }
}
