package com.opensab.domain.settings

import kotlinx.serialization.Serializable

@Serializable
enum class InboundMessageMode {
    /** Mounts MessagePageProvider directly and reads text out loud via TTS. */
    READ_DIRECTLY,

    /** Mounts MessagePromptPageProvider and asks rider before reading (default). */
    PROMPT,

    /** Does not take the cluster screen; only turns on the MESSAGE indicator and keeps root menu access. */
    IGNORE
}

@Serializable
enum class InboundCallMode {
    /** Takes the cluster screen and alerts the rider (default). */
    ALERT,

    /** Does not take the cluster screen; only turns on the PHONE indicator and keeps root menu access. */
    IGNORE
}
