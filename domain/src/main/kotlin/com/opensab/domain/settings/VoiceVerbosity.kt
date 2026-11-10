package com.opensab.domain.settings

import kotlinx.serialization.Serializable

@Serializable
enum class VoiceVerbosity {
    /** Nothing is spoken. Earcons still play if enabled: the two switches are independent. */
    NONE,

    /** Essential information only (caller, sender and text, forecast); no button instructions. */
    STANDARD,

    /**
     * Essential information plus a short pause and contextual handlebar instructions
     * (e.g. "Press Select to reply. Press Back to dismiss.").
     */
    ADVANCED
}
