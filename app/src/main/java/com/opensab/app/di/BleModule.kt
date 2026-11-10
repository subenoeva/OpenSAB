package com.opensab.app.di

import android.content.Context
import com.opensab.app.ble.AndroidBleScanner
import com.opensab.app.ble.AndroidBondController
import com.opensab.app.ble.AndroidGattTransportFactory
import com.opensab.app.ble.GattTransportFactory
import com.opensab.app.ble.cts.AndroidCtsServer
import com.opensab.sab.session.ble.BleScanner
import com.opensab.sab.session.ble.DefaultDeviceBonder
import com.opensab.sab.session.ble.DeviceBonder
import com.opensab.sab.session.ble.cts.CtsServer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    @Provides
    @Singleton
    fun provideBondController(
        @ApplicationContext context: Context
    ): AndroidBondController = AndroidBondController(context)

    @Provides
    @Singleton
    fun provideDeviceBonder(
        bondController: AndroidBondController
    ): DeviceBonder = DefaultDeviceBonder(bondController)

    @Provides
    @Singleton
    fun provideGattTransportFactory(
        @ApplicationContext context: Context
    ): GattTransportFactory = AndroidGattTransportFactory(context)

    @Provides
    @Singleton
    fun provideBleScanner(
        @ApplicationContext context: Context
    ): BleScanner = AndroidBleScanner(context)

    @Provides
    @Singleton
    fun provideCtsServer(
        @ApplicationContext context: Context
    ): CtsServer = AndroidCtsServer(context)
}
