package com.opensab.domain.music

import kotlinx.coroutines.flow.StateFlow

data class VolumeLevel(
    val percent: Int = 100,
    val isMuted: Boolean = false
)

interface VolumeSource {

    val level: StateFlow<VolumeLevel>

    fun start()

    fun stop()

    fun raise()
    fun lower()
    fun toggleMute()
}
