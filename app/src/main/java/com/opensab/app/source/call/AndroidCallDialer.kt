package com.opensab.app.source.call

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import com.opensab.domain.call.CallDialer
import com.opensab.logging.Log

/**
 * Turns a number into a `tel:` intent and reports whether the system took it.
 *
 * The two `catch`es are named rather than wide on purpose. A `catch (e: Exception)` would also
 * swallow `CancellationException`: leaving the page mid-dial would come back as "call failed" for a
 * call nobody is waiting for any more.
 */
class AndroidCallDialer(private val context: Context) : CallDialer {

    override suspend fun place(number: String): Boolean {
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom == null) {
            Log.w("This phone has no TelecomManager, so there is nothing to dial with")
            return false
        }
        return try {
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
            Log.d("The call is with the system dialler")
            true
        } catch (e: SecurityException) {
            Log.w(e, "CALL_PHONE is not granted, so the call cannot be placed")
            false
        } catch (e: UnsupportedOperationException) {
            Log.w(e, "This device cannot place calls")
            false
        }
    }
}
