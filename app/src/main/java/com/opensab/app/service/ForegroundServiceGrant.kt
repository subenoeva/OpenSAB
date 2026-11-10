package com.opensab.app.service

import android.content.pm.ServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The foreground service type the system actually granted to [SabConnectionService], which is not
 * always the one it asked for: started from the ACL receiver with the app out of sight, Android 14+
 * refuses `microphone` and the service falls back to `connectedDevice` alone.
 *
 * **Owner: this class, and only [SabConnectionService] writes it** — [publish] after every
 * `startForeground`, [clear] when it leaves the foreground. Everyone else reads: the speech gate
 * ([microphoneUsable]) and the promotion when the UI comes to the front ([needsPromotion]).
 *
 * `null` is "no service in the foreground", and so no session.
 *
 * The type alone does not say whether the microphone works. Android 14+ throws when it refuses
 * `microphone`, so a published bit is proof. Android 11 to 13 accept the bit from the background and
 * silence the recording anyway, so there it counts only if it was taken with the app visible.
 */
class ForegroundServiceGrant(private val sdkInt: Int) {

    private val _type = MutableStateFlow<Int?>(null)
    val type: StateFlow<Int?> = _type.asStateFlow()

    @Volatile
    private var startedVisible = false

    fun publish(obtained: Int, startedVisible: Boolean) {
        this.startedVisible = startedVisible
        _type.value = obtained
    }

    fun clear() {
        _type.value = null
    }

    /** Below Android 11 the microphone has no foreground rule at all. */
    fun microphoneUsable(): Boolean {
        if (sdkInt < Build.VERSION_CODES.R) return true
        val granted = _type.value ?: return false
        if (granted and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE == 0) return false
        return sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || startedVisible
    }

    /**
     * A live service lacks some type in [wanted], or holds a microphone Android 11-13 silence:
     * calling `startForeground` again while visible fixes both.
     */
    fun needsPromotion(wanted: Int): Boolean {
        val granted = _type.value ?: return false
        if (wanted and granted.inv() != 0) return true
        if (sdkInt < Build.VERSION_CODES.R) return false
        if (wanted and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE == 0) return false
        return !microphoneUsable()
    }
}
