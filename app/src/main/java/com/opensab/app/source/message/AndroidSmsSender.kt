package com.opensab.app.source.message

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.opensab.domain.message.SmsSender
import com.opensab.logging.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * Suspends until every part of the split message reports its sent broadcast, or 10 s pass.
 */
class AndroidSmsSender(
    private val context: Context
) : SmsSender {

    override suspend fun send(recipientNumber: String, text: String): Boolean {
        if (recipientNumber.isBlank()) {
            Log.w("Cannot send SMS: blank recipient number")
            return false
        }

        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        @Suppress("DEPRECATION")
        val isSmsCapable = telephonyManager?.isSmsCapable == true
        if (!isSmsCapable) {
            Log.w("Device is not SMS capable")
            return false
        }

        val smsManager: SmsManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        if (smsManager == null) {
            Log.w("No SmsManager available on this device")
            return false
        }

        val action = "com.opensab.source.message.SMS_SENT_${UUID.randomUUID()}"
        val parts = smsManager.divideMessage(text)
        val expectedParts = if (parts.isEmpty()) 1 else parts.size

        val completed = AtomicBoolean(false)
        val successfulParts = AtomicInteger(0)

        var isReceiverRegistered = false
        val continuationRef = AtomicReference<CancellableContinuation<Boolean>?>(null)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (resultCode == Activity.RESULT_OK) {
                    if (successfulParts.incrementAndGet() == expectedParts) {
                        complete(true)
                    }
                } else {
                    Log.w("SMS part send failed with resultCode=%d", resultCode)
                    complete(false)
                }
            }

            private fun complete(result: Boolean) {
                if (completed.compareAndSet(false, true)) {
                    val cont = continuationRef.get()
                    if (cont != null && cont.isActive) {
                        cont.resume(result)
                    }
                }
            }
        }

        val receiverFlags = ContextCompat.RECEIVER_NOT_EXPORTED

        return try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(action),
                receiverFlags
            )
            isReceiverRegistered = true

            val sendResult = withTimeoutOrNull(SEND_TIMEOUT) {
                suspendCancellableCoroutine { continuation ->
                    continuationRef.set(continuation)

                    continuation.invokeOnCancellation {
                        completed.set(true)
                    }

                    try {
                        if (parts.size > 1) {
                            val sentIntents = ArrayList<PendingIntent>(parts.size)
                            for (i in parts.indices) {
                                sentIntents.add(
                                    PendingIntent.getBroadcast(
                                        context,
                                        i,
                                        Intent(action).setPackage(context.packageName),
                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                    )
                                )
                            }
                            smsManager.sendMultipartTextMessage(
                                recipientNumber,
                                null,
                                parts,
                                sentIntents,
                                null
                            )
                        } else {
                            val singleText = if (parts.isEmpty()) text else parts[0]
                            val sentIntent = PendingIntent.getBroadcast(
                                context,
                                0,
                                Intent(action).setPackage(context.packageName),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            smsManager.sendTextMessage(
                                recipientNumber,
                                null,
                                singleText,
                                sentIntent,
                                null
                            )
                        }
                    } catch (e: SecurityException) {
                        Log.w(e, "SEND_SMS permission not granted")
                        if (completed.compareAndSet(false, true)) {
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(e, "Exception while initiating SMS send")
                        if (completed.compareAndSet(false, true)) {
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }
                }
            }

            sendResult ?: run {
                Log.w("SMS send timed out after %d ms", SEND_TIMEOUT.inWholeMilliseconds)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(e, "SEND_SMS permission check failed")
            false
        } catch (e: Exception) {
            Log.e(e, "Unexpected error during SMS send")
            false
        } finally {
            if (isReceiverRegistered) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    Log.w(e, "Failed to unregister SMS broadcast receiver")
                }
            }
        }
    }

    companion object {
        private val SEND_TIMEOUT = 10.seconds
    }
}
