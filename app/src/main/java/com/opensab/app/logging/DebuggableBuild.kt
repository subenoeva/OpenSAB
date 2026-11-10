package com.opensab.app.logging

import android.content.Context
import android.content.pm.ApplicationInfo
import javax.inject.Qualifier

/**
 * The one gate between a build that records a session journal and one that records nothing. Read
 * from the installed package rather than `BuildConfig.DEBUG`, so the application and the Hilt graph
 * cannot disagree about it.
 */
fun Context.isDebuggableBuild(): Boolean =
    applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/** Marks the Boolean that answers [isDebuggableBuild]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DebuggableBuild
