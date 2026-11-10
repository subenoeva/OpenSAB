package com.opensab.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.opensab.app.MainActivity
import com.opensab.app.R
import com.opensab.logging.Log
import com.opensab.sab.session.SabSessionController
import com.opensab.sab.session.SabSessionState
import com.opensab.sab.session.SessionCommand
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground Service maintaining the permanent Bluetooth Low Energy connection with the motorcycle.
 *
 * The one entry point a [SessionCommand] reaches [SabSessionController] through: `onStartCommand`
 * only decodes ([SessionIntents]) and submits, it never opens a session itself.
 */
@AndroidEntryPoint
class SabConnectionService : Service() {

    @Inject
    lateinit var controller: SabSessionController

    /** Written only from here: [enterForeground] publishes, leaving the foreground clears. */
    @Inject
    lateinit var grant: ForegroundServiceGrant

    @Inject
    lateinit var visibility: AppVisibility

    /**
     * Mounted once in [onCreate], not once per `START`: a per-`START` collector of `sessionState`
     * never dies until `onDestroy`, so a session that lives through several `START`s would leave as
     * many collectors running.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        const val CHANNEL_ID = "opensab_connection_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_CONNECT = "com.opensab.service.START_CONNECT"
        const val ACTION_DISCONNECT = "com.opensab.service.DISCONNECT"

        /**
         * Sent by [ForegroundPromoter] while the app is visible: repeat `startForeground` so a
         * service that was refused `microphone` from the background gets it now. Opens nothing.
         */
        const val ACTION_PROMOTE = "com.opensab.service.PROMOTE"
        const val EXTRA_MAC_ADDRESS = "extra_mac_address"
        const val EXTRA_ORIGIN = "extra_origin"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // One collector setup for the whole life of the service. The connected-notification and the
        // idle-driven stop are, deliberately, the same mounting call: a nested
        // collector off session state for the notification, and the outer collector off `idle` for
        // stopSelf(startId) — so there is exactly one place, not one per START, either can leak from.
        serviceScope.launch {
            launch {
                var wasConnected = false
                controller.state.collect { state ->
                    if (state is SabSessionState.Connected && !wasConnected) {
                        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(
                            NOTIFICATION_ID,
                            buildOngoingNotification(getString(R.string.notification_connected_text))
                        )
                    }
                    wasConnected = state is SabSessionState.Connected
                }
            }

            controller.idle.collect { startId ->
                when (val state = controller.state.value) {
                    is SabSessionState.Error ->
                        Log.w("Session encountered Error: %s, stopping SabConnectionService", state.cause.message)
                    else ->
                        Log.i("Session is Disconnected, stopping SabConnectionService")
                }
                Log.i("SabConnectionService idle after startId=%d, stopping", startId)
                stopForeground(STOP_FOREGROUND_REMOVE)
                grant.clear()
                stopSelf(startId)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        grant.clear()
        serviceScope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("SabConnectionService onStartCommand with action: $action")

        if (intent == null || action == null) {
            Log.d("SabConnectionService started with null intent or action, stopping self")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (action == ACTION_PROMOTE) {
            when (val request = PromotionRequest.of(grant.type.value, controller.state.value is SabSessionState.Connected)) {
                PromotionRequest.StopSelf -> {
                    Log.i("SabConnectionService PROMOTE with no session in the foreground, stopping self")
                    stopSelf(startId)
                }
                is PromotionRequest.EnterForeground -> enterForeground(
                    if (request.connected) R.string.notification_connected_text else R.string.notification_connecting_text
                )
            }
            return START_NOT_STICKY
        }

        val mac = intent.getStringExtra(EXTRA_MAC_ADDRESS)
        val origin = intent.getStringExtra(EXTRA_ORIGIN)
        val command = SessionIntents.decode(action, mac, origin, startId)

        if (command == null) {
            when (action) {
                ACTION_START_CONNECT -> Log.w("SabConnectionService START_CONNECT with null MAC, stopping self")
                else -> Log.w("Unknown action for SabConnectionService: $action, stopping self")
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (command) {
            is SessionCommand.Start -> {
                enterForeground(R.string.notification_connecting_text)
                Log.i("Starting connection for MAC: ${command.macAddress}")
                controller.submit(command)
            }
            is SessionCommand.Disconnect -> {
                Log.i("Stopping connection service")
                controller.submit(command)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * `startForeground` with every type the permissions allow, falling back to `connectedDevice`
     * when the system refuses the rest, and publishing what was obtained — never what was asked.
     */
    private fun enterForeground(statusText: Int) {
        val notification = buildOngoingNotification(getString(statusText))
        val obtained = ForegroundServiceTypeResolver.startWithFallback(wantedForegroundServiceType()) { type ->
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }
        grant.publish(obtained, startedVisible = visibility.isVisible())
        Log.i("SabConnectionService in foreground with type %d", obtained)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildOngoingNotification(statusText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
