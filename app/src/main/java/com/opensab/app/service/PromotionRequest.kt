package com.opensab.app.service

/**
 * What [SabConnectionService] does with an `ACTION_PROMOTE`. With no type granted there is no
 * session: the start that delivered the PROMOTE created the service, and it stops again.
 */
sealed interface PromotionRequest {
    data object StopSelf : PromotionRequest
    data class EnterForeground(val connected: Boolean) : PromotionRequest

    companion object {
        fun of(grantedType: Int?, connected: Boolean): PromotionRequest =
            if (grantedType == null) StopSelf else EnterForeground(connected)
    }
}
