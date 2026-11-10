package com.opensab.app.ui.sessions

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Shares an exported session zip through the app's own [FileProvider]. */
object ShareZip {
    const val AUTHORITY_SUFFIX = ".journal"
    const val MIME = "application/zip"

    /** Pure: what the Intent must carry, so it can be tested without Android. */
    data class Spec(val authority: String, val mime: String, val grantRead: Boolean)

    fun spec(applicationId: String): Spec = Spec(applicationId + AUTHORITY_SUFFIX, MIME, grantRead = true)

    /** Thin adapter: FileProvider URI, ACTION_SEND, FLAG_GRANT_READ_URI_PERMISSION, chooser. */
    fun launch(context: Context, zip: File) {
        val spec = spec(context.packageName)
        val uri = FileProvider.getUriForFile(context, spec.authority, zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = spec.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
