package com.opensab.app.service

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.opensab.logging.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * A black window that exists for one second, so the session the ACL receiver opens is started
 * with the app on top and its foreground service is granted the microphone.
 *
 * It paints nothing and decides nothing: [SessionBootstrapper] owns the pending START. Once resumed
 * it waits a second, claims the START and
 * finishes. If the START has already left through the fallback, being visible is still worth
 * something, and it asks [ForegroundPromoter] to upgrade the service instead.
 */
@AndroidEntryPoint
class SessionBootstrapActivity : ComponentActivity() {

    @Inject
    lateinit var bootstrapper: SessionBootstrapper

    @Inject
    lateinit var foregroundPromoter: ForegroundPromoter

    private var claimed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screenOff = !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val locked = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val flags = SessionBootstrapWindow.of(screenOff, locked)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(flags.showWhenLocked)
            setTurnScreenOn(flags.turnScreenOn)
        } else {
            @Suppress("DEPRECATION")
            if (flags.showWhenLocked) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            @Suppress("DEPRECATION")
            if (flags.turnScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        Log.i("Session bootstrap activity created (screenOff=%s, locked=%s)", screenOff, locked)
    }

    override fun onResume() {
        super.onResume()
        if (claimed) return
        claimed = true
        lifecycleScope.launch {
            delay(VISIBLE_BEFORE_START)
            if (!bootstrapper.startClaimed()) foregroundPromoter.onUiVisible()
            finish()
        }
    }

    companion object {
        private val VISIBLE_BEFORE_START = 1.seconds

        /** False when the system refuses the launch outright; a silent block is the bootstrapper's timeout. */
        fun launch(context: Context): Boolean = try {
            context.startActivity(
                Intent(context, SessionBootstrapActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            true
        } catch (e: Exception) {
            Log.w(e, "Session bootstrap activity launch refused")
            false
        }
    }
}
