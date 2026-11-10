package com.opensab.domain.music

/**
 * [MediaBrowserItem] deliberately carries no title: the cold start engine never draws anything,
 * it only decides what to dispatch. A picker the rider reads does need one.
 */
data class MediaLibraryNode(
    val mediaId: String,
    val title: String,
    val subtitle: String?,
    val isPlayable: Boolean,
    val isBrowsable: Boolean
)

/**
 * Separate from [MediaBrowserConnection] because the two have different lifetimes: this one runs
 * with the rider watching and able to cancel, so it suspends; the other is headless, guarded by
 * watchdogs and a wakelock, so it uses callbacks.
 */
interface MediaLibraryBrowser {
    /** The browse root ID, or null when the service does not answer. */
    suspend fun connect(packageName: String): String?

    /** Empty when not connected or the app reports an error. */
    suspend fun browse(parentId: String): List<MediaLibraryNode>

    fun disconnect()
}
