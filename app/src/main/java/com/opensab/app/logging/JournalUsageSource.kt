package com.opensab.app.logging

import com.opensab.logging.journal.JournalReader
import com.opensab.logging.journal.JournalUsage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * The settings screen's window into the journal's usage. The only caller of
 * [JournalReader.setUsageListener]: nothing else may claim that single listener slot.
 */
class JournalUsageSource @Inject constructor(private val reader: JournalReader) {

    val usage: Flow<JournalUsage> = callbackFlow {
        trySend(reader.usage)
        reader.setUsageListener { trySend(it) }
        awaitClose { reader.setUsageListener(null) }
    }
}
