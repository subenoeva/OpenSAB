package com.opensab.app.di

import com.opensab.app.service.SessionBootstrapper
import com.opensab.domain.autoconnect.AclPresence
import com.opensab.domain.settings.SettingsRepository
import com.opensab.sab.session.SabSessions
import com.opensab.sab.session.vehicle.VehicleRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Manual access to the singleton graph for components Hilt cannot field-inject from Kotlin.
 *
 * `@AndroidEntryPoint` on a [android.content.BroadcastReceiver] requires calling
 * `super.onReceive(...)`, which Kotlin rejects because [android.content.BroadcastReceiver.onReceive]
 * is abstract at compile time — the Hilt superclass is only spliced in afterwards by the bytecode
 * transform. Pulling the dependencies through an entry point keeps the receiver on the same graph
 * without that seam.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReceiverEntryPoint {
    fun vehicleRepository(): VehicleRepository
    fun settingsRepository(): SettingsRepository
    fun sabSessions(): SabSessions
    fun sessionBootstrapper(): SessionBootstrapper
    fun aclPresence(): AclPresence
}
