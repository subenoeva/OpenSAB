package com.opensab.domain.nav

import kotlinx.coroutines.flow.Flow

/** One element each time the rider unlocks the phone, only while someone collects. */
interface DeviceUnlockSource {
    val unlocks: Flow<Unit>
}
