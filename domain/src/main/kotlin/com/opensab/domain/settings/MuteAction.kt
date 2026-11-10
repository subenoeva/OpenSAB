package com.opensab.domain.settings

import kotlinx.serialization.Serializable

@Serializable
enum class MuteAction {
    PAUSE_MUSIC,
    MUTE_ALL
}
