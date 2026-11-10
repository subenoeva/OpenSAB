package com.opensab.app.di

import com.opensab.app.ui.permissions.AndroidPermissionProbe
import com.opensab.app.ui.permissions.DefaultPermissionStatusReader
import com.opensab.app.ui.permissions.PermissionCatalog
import com.opensab.app.ui.permissions.PermissionEntry
import com.opensab.app.ui.permissions.PermissionProbe
import com.opensab.app.ui.permissions.PermissionStatusReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The permissions screen's graph: the facts, the rule, and the rows the rule is asked about.
 *
 * The API level enters exactly here, from [PermissionProbe.sdkInt], and nowhere else.
 * [PermissionCatalog.entriesFor] has no default argument precisely so that this is the only place
 * that can answer "which Android is this".
 */
@Module
@InstallIn(SingletonComponent::class)
object PermissionsModule {

    @Provides
    @Singleton
    fun providePermissionProbe(probe: AndroidPermissionProbe): PermissionProbe = probe

    @Provides
    @Singleton
    fun providePermissionStatusReader(probe: PermissionProbe): PermissionStatusReader =
        DefaultPermissionStatusReader(probe)

    /**
     * The rows this phone can show. Resolved once for the process: the API level cannot change
     * under a running app, and a list rebuilt per screen would be four chances to disagree.
     */
    @Provides
    @Singleton
    fun providePermissionEntries(probe: PermissionProbe): List<PermissionEntry> =
        PermissionCatalog.entriesFor(probe.sdkInt)
}
