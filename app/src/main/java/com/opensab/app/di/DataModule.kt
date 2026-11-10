package com.opensab.app.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.opensab.app.persistence.DataStoreSettingsRepository
import com.opensab.app.persistence.DataStoreVehicleRepository
import com.opensab.domain.settings.SettingsRepository
import com.opensab.sab.session.vehicle.VehicleRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideVehicleRepository(
        dataStore: DataStore<Preferences>
    ): VehicleRepository = DataStoreVehicleRepository(dataStore)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        dataStore: DataStore<Preferences>
    ): SettingsRepository = DataStoreSettingsRepository(dataStore)
}
