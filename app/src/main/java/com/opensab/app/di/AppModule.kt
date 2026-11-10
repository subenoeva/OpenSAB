package com.opensab.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.opensab.app.crypto.CipheredPrefsKeyValueStore
import com.opensab.app.logging.DebuggableBuild
import com.opensab.app.storage.CorruptStoreArchive
import com.opensab.domain.settings.SettingsRepository
import com.opensab.domain.settings.resolveVerboseLogging
import com.opensab.logging.Log
import com.opensab.sab.session.crypto.PersistentSecureKeyStore
import com.opensab.sab.session.crypto.SecureKeyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Qualifier
import javax.inject.Singleton

/** The verbose log switch, resolved, for `TimberLogSink`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VerboseLogging

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val VEHICLE_STORE_NAME = "vehicles"

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The one `DataStore<Preferences>` of the app. It is named after the motorcycles because they
     * came first, but the settings, the favourite player and the weather cache live in it too —
     * which is what makes the corruption handler below as broad as it is.
     *
     * Replacing an unreadable store is still the right call: the alternative is an app that will
     * not launch. Doing it in silence is not: a key can disappear leaving no evidence but a smaller
     * file. So the bytes are archived first and the loss is logged at ERROR, naming everything that
     * goes with it.
     */
    @Provides
    @Singleton
    fun provideVehicleDataStore(
        @ApplicationContext context: Context,
        persistenceScope: CoroutineScope
    ): DataStore<Preferences> {
        val produceFile = { context.preferencesDataStoreFile(VEHICLE_STORE_NAME) }
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { exception ->
                Log.e(exception, "The preferences store could not be read and is being replaced")
                CorruptStoreArchive.preserve(produceFile(), System.currentTimeMillis())
                emptyPreferences()
            },
            scope = persistenceScope,
            produceFile = produceFile
        )
    }

    @Provides
    @Singleton
    fun provideSecureKeyStore(
        @ApplicationContext context: Context
    ): SecureKeyStore = PersistentSecureKeyStore(CipheredPrefsKeyValueStore(context))

    /** Starts at the build default until DataStore answers. */
    @Provides
    @Singleton
    @VerboseLogging
    fun provideVerboseLogging(
        settingsRepository: SettingsRepository,
        applicationScope: CoroutineScope,
        @DebuggableBuild debuggable: Boolean
    ): StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { resolveVerboseLogging(it.verboseLogging, debuggable) }
        .stateIn(applicationScope, SharingStarted.Eagerly, debuggable)
}
