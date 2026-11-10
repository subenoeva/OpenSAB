package com.opensab.app.source.music

/** A connection to use instead of a real `MediaBrowserCompat` one, or null to connect for real. */
fun interface MediaBrowserConnectionOverride {
    fun connectionFor(packageName: String): MediaBrowserConnection?
}

/** What release binds: every connection is real. */
object NoMediaBrowserConnectionOverride : MediaBrowserConnectionOverride {
    override fun connectionFor(packageName: String): MediaBrowserConnection? = null
}
