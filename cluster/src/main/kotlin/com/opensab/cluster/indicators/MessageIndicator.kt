package com.opensab.cluster.indicators

import com.opensab.domain.message.MessageInfo
import com.opensab.sab.protocol.projection.ActiveIndicator
import com.opensab.sab.session.projection.ActiveIndicatorsPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * MESSAGE stays lit while a message is unread — which outlives the page, unlike every other bit in
 * the bar. Yielding the screen keeps the message, so it keeps the bit.
 */
class MessageIndicator(
    private val currentMessage: StateFlow<MessageInfo?>,
    private val publisher: ActiveIndicatorsPublisher
) : IndicatorTranslator {

    override val name: String = "message"

    override fun start(scope: CoroutineScope) {
        scope.launch { currentMessage.collect { publisher.set(ActiveIndicator.MESSAGE, it != null) } }
    }
}
