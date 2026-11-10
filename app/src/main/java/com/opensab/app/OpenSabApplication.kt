package com.opensab.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.opensab.app.di.VerboseLogging
import com.opensab.app.logging.FacadeAwareDebugTree
import com.opensab.app.logging.FileLoggingTree
import com.opensab.app.logging.JournalLimit
import com.opensab.app.logging.TimberLogSink
import com.opensab.app.logging.isDebuggableBuild
import com.opensab.app.service.AppVisibility
import com.opensab.logging.Log
import com.opensab.logging.journal.JournalReader
import com.opensab.logging.journal.NoSessionJournal
import com.opensab.logging.journal.SessionJournal
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject

/**
 * Owns logging and the only instance of the session journal. In debuggable builds it installs the
 * only [Log] sink, creates the journal and plants Timber's trees; Hilt re-exposes [journalReader]
 * rather than building a journal of its own. A release build creates, plants and installs nothing,
 * so it logs nothing and [journalReader] is [NoSessionJournal].
 *
 * The order in [onCreate] matters: [JournalLimit] and [verboseLogging] start reading the settings
 * the moment they are resolved, and that read can log, so the sink is installed first; the
 * journal has to exist before the tree that writes to it is planted.
 *
 * It also feeds [AppVisibility] from the activity lifecycle, in every build.
 */
@HiltAndroidApp
class OpenSabApplication : Application() {

    /** Lazy so that a release build never starts reading a limit nobody uses. */
    @Inject
    lateinit var journalLimit: Lazy<JournalLimit>

    var journalReader: JournalReader = NoSessionJournal
        private set

    /** Lazy for the same reason: resolving it starts the settings read, which can log. */
    @Inject
    @VerboseLogging
    lateinit var verboseLogging: Lazy<StateFlow<Boolean>>

    @Inject
    lateinit var appVisibility: AppVisibility

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = appVisibility.onResumed()
            override fun onActivityPaused(activity: Activity) = appVisibility.onPaused()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        if (isDebuggableBuild()) {
            Timber.plant(FacadeAwareDebugTree())
            Log.install(TimberLogSink { verboseLogging.get().value })
            verboseLogging.get()
            val limit = journalLimit.get()
            val logDir = getExternalFilesDir(null)?.resolve("logs") ?: filesDir.resolve("logs")
            val journal = SessionJournal.create(logDir, cacheDir.resolve("exports")) { limit.journalBytes }
            journalReader = journal
            Timber.plant(FileLoggingTree(journal))
            Timber.i("OpenSAB starting up, debug and session journal enabled at %s", logDir.absolutePath)
        }
    }
}
