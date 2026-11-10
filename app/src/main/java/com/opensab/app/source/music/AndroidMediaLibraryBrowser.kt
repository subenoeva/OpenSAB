@file:Suppress("DEPRECATION")

package com.opensab.app.source.music

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import com.opensab.domain.music.MediaLibraryBrowser
import com.opensab.domain.music.MediaLibraryNode
import com.opensab.logging.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Walks a media app's browse tree for the settings picker.
 *
 * Every callback is guarded by an [AtomicBoolean]: `MediaBrowserCompat` may deliver
 * `onChildrenLoaded` more than once for one subscription, and can report an error after a
 * successful connect. Resuming a continuation twice throws, so each one resumes at most once.
 */
class AndroidMediaLibraryBrowser(private val context: Context) : MediaLibraryBrowser {

    private val handler = Handler(Looper.getMainLooper())
    private var browser: MediaBrowserCompat? = null

    override suspend fun connect(packageName: String): String? =
        suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            fun finish(root: String?) {
                if (resumed.compareAndSet(false, true)) continuation.resume(root)
            }

            handler.post {
                val component = MediaAppScannerDefaults.resolveBrowserComponent(
                    packageName = packageName,
                    fromPackageManager = resolveComponentFromPackageManager(packageName)
                )?.toComponentName()
                if (component == null) {
                    Log.w("No MediaBrowserService found for $packageName")
                    finish(null)
                    return@post
                }

                // Root hints as Android Auto sends them, as the cold start engine does.
                val rootHints = Bundle().apply {
                    putBoolean("android.service.media.extra.SUGGESTIONS", true)
                    putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                }

                val callback = object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() {
                        finish(browser?.root)
                    }

                    override fun onConnectionFailed() {
                        Log.w("Library browse connection failed for $packageName")
                        finish(null)
                    }

                    override fun onConnectionSuspended() {
                        finish(null)
                    }
                }

                try {
                    browser = MediaBrowserCompat(context, component, callback, rootHints)
                        .apply { connect() }
                } catch (t: Throwable) {
                    Log.w(t, "Could not connect a library browser to $packageName")
                    finish(null)
                }
            }

            continuation.invokeOnCancellation { disconnect() }
        }

    override suspend fun browse(parentId: String): List<MediaLibraryNode> =
        suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            fun finish(nodes: List<MediaLibraryNode>) {
                if (resumed.compareAndSet(false, true)) continuation.resume(nodes)
            }

            handler.post {
                val active = browser
                if (active == null || !active.isConnected) {
                    finish(emptyList())
                    return@post
                }

                active.subscribe(parentId, object : MediaBrowserCompat.SubscriptionCallback() {
                    override fun onChildrenLoaded(
                        parentId: String,
                        children: MutableList<MediaBrowserCompat.MediaItem>
                    ) {
                        // Read once, then stop listening: later updates have no continuation left.
                        try {
                            active.unsubscribe(parentId)
                        } catch (_: Throwable) {}
                        finish(
                            children.map { item ->
                                MediaLibraryNode(
                                    mediaId = item.mediaId.orEmpty(),
                                    title = item.description.title?.toString().orEmpty(),
                                    subtitle = item.description.subtitle?.toString(),
                                    isPlayable = item.isPlayable,
                                    isBrowsable = item.isBrowsable
                                )
                            }
                        )
                    }

                    override fun onError(parentId: String) {
                        Log.w("Library browse error under $parentId")
                        try {
                            active.unsubscribe(parentId)
                        } catch (_: Throwable) {}
                        finish(emptyList())
                    }
                })
            }
        }

    override fun disconnect() {
        handler.post {
            try {
                browser?.disconnect()
            } catch (t: Throwable) {
                Log.w(t, "Error disconnecting the library browser")
            } finally {
                browser = null
            }
        }
    }

    private fun resolveComponentFromPackageManager(packageName: String): MediaServiceComponent? {
        val intent = android.content.Intent("android.media.browse.MediaBrowserService")
            .setPackage(packageName)
        val services = context.packageManager.queryIntentServices(intent, 0)
        val info = services.firstOrNull()?.serviceInfo ?: return null
        return MediaServiceComponent(info.packageName, info.name)
    }
}
