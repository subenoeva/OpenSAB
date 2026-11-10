package com.opensab.app.di

import android.Manifest
import android.content.Context
import com.opensab.app.source.call.AndroidCallDialer
import com.opensab.app.source.contacts.AndroidContactDirectory
import com.opensab.app.ui.permissions.PermissionProbe
import com.opensab.domain.call.CallDialer
import com.opensab.domain.contacts.CallMenuAccess
import com.opensab.domain.contacts.ContactDirectory
import com.opensab.domain.contacts.DefaultCallMenuAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The address book, the dialler, and the rule that decides whether the two rows of the call menu
 * open.
 *
 * The two permissions enter as functions rather than as the probe itself, so `source/contacts` never
 * has to see `com.opensab.app.ui.permissions`: the dependency points this way round, from the graph into
 * the domain, and never back.
 */
@Module
@InstallIn(SingletonComponent::class)
object ContactsModule {

    @Provides
    @Singleton
    fun provideContactDirectory(@ApplicationContext context: Context): ContactDirectory =
        AndroidContactDirectory(context.contentResolver)

    @Provides
    @Singleton
    fun provideCallDialer(@ApplicationContext context: Context): CallDialer =
        AndroidCallDialer(context)

    @Provides
    @Singleton
    fun provideCallMenuAccess(
        probe: PermissionProbe,
        directory: ContactDirectory
    ): CallMenuAccess = DefaultCallMenuAccess(
        contactsGranted = { probe.isPermissionGranted(Manifest.permission.READ_CONTACTS) },
        phoneGranted = { probe.isPermissionGranted(Manifest.permission.CALL_PHONE) },
        directory = directory
    )
}
