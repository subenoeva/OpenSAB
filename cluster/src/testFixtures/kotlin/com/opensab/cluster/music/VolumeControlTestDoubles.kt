package com.opensab.cluster.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** "Handlebar Volume Control" on, its default: the pages behave as they did before the setting. */
val volumeControlOn: StateFlow<Boolean> = MutableStateFlow(true)
