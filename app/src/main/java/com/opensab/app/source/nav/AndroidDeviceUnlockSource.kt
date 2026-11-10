package com.opensab.app.source.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.opensab.domain.nav.DeviceUnlockSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [Intent.ACTION_USER_PRESENT] as a flow.
 *
 * **Owner of the receiver: this adapter**, one registration per collector, from the first
 * collection until it is cancelled. Nothing stays registered while nobody waits for an unlock.
 *
 * Exported because SystemUI, not the system uid, sends the broadcast: a not-exported receiver never
 * gets it. The action is protected, so no app can fake it.
 */
class AndroidDeviceUnlockSource(private val context: Context) : DeviceUnlockSource {

    override val unlocks: Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED
        )
        awaitClose { context.unregisterReceiver(receiver) }
    }
}
