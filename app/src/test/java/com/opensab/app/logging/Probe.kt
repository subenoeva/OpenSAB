package com.opensab.app.logging

import com.opensab.logging.Log
import timber.log.Timber

/**
 * Calls Timber and the facade from the same frames, so both must produce the same tag. Names stay under
 * 23 characters so Timber's pre-API-26 cut never reads `Build.VERSION` on the JVM.
 */
internal class Probe {

    fun method(viaFacade: Boolean, t: Throwable?, message: String?, vararg args: Any?) {
        if (viaFacade) Log.w(t, message, *args) else Timber.w(t, message, *args)
    }

    fun anon(viaFacade: Boolean) {
        object : Runnable {
            override fun run() {
                if (viaFacade) Log.i("from an anonymous object") else Timber.i("from an anonymous object")
            }
        }.run()
    }
}

internal fun topLevel(viaFacade: Boolean) {
    if (viaFacade) Log.d("from a top-level function") else Timber.d("from a top-level function")
}
