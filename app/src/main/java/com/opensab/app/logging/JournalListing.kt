package com.opensab.app.logging

import com.opensab.logging.journal.ExportRequest
import com.opensab.logging.journal.JournalReader
import com.opensab.logging.journal.SessionListing
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * The session screen's suspend view of [JournalReader]. Each callback resumes the caller from the
 * journal's executor thread; the caller's own dispatcher takes it back from there. A caller cancelled
 * meanwhile simply drops the answer.
 */
class JournalListing @Inject constructor(private val reader: JournalReader) {

    suspend fun list(): List<SessionListing> = suspendCancellableCoroutine { continuation ->
        reader.listSessions { continuation.resume(it) }
    }

    suspend fun export(request: ExportRequest): Result<File> = suspendCancellableCoroutine { continuation ->
        reader.export(request) { continuation.resume(it) }
    }
}
