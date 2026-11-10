package com.opensab.app.di

import android.content.Context
import com.opensab.app.OpenSabApplication
import com.opensab.app.logging.DebuggableBuild
import com.opensab.app.logging.isDebuggableBuild
import com.opensab.logging.journal.JournalReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Re-exposes the journal [OpenSabApplication] created; the graph never builds one of its own.
 */
@Module
@InstallIn(SingletonComponent::class)
object JournalModule {

    @Provides
    @Singleton
    fun provideJournalReader(@ApplicationContext context: Context): JournalReader =
        (context as OpenSabApplication).journalReader

    @Provides
    @DebuggableBuild
    fun provideDebuggable(@ApplicationContext context: Context): Boolean = context.isDebuggableBuild()
}
