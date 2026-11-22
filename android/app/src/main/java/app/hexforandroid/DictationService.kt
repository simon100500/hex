package app.hexforandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps Hex armed: a microphone foreground service, started while the app is
 * in front, so Android lets the bubble record later from any app. It also
 * keeps the model loaded. Recording itself starts only on a bubble press.
 */
class DictationService : Service() {
    private var shown: List<Any?>? = null
    private val listener: (Dictation.State) -> Unit = { state ->
        // The level changes 50 times a second while recording; the notification needn't.
        if (state.shape != shown) {
            shown = state.shape
            notify(state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Dictation.init(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hex dictation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Hex is ready to dictate"
                setShowBadge(false)
            },
        )
        try {
            startForeground(NOTIFICATION, notification(Dictation.state), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // No microphone permission, or Android won't start a microphone
            // service from the background right now.
            android.util.Log.w("HexService", "could not start in the foreground", e)
            stopSelf()
            return
        }
        running = true
        Settings(this).enabled = true
        Dictation.arm(this)
        Dictation.listen(listener)
        Vocabulary.sync(this, minGapMs = 60_000)
        HexAccessibilityService.instance?.refreshBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started again while running (the bubble re-arming after a failed load).
        if (running && intent?.action == null && !Dictation.armed) Dictation.arm(this)
        when (intent?.action) {
            ACTION_OFF -> {
                Settings(this).enabled = false
                stopSelf()
            }
            ACTION_PASTE_LAST -> {
                val last = History.items(this).firstOrNull()?.text
                // Close the notification shade, then let focus return to the field.
                HexAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                if (last != null) Handler(Looper.getMainLooper()).postDelayed({ Delivery.deliver(this, last) }, 600)
            }
        }
        // Android 14 refuses a microphone service restarted from the background,
        // so a killed service stays down until the bubble or the app arms it again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Dictation.unlisten(listener)
        if (running) Dictation.disarm()
        running = false
        HexAccessibilityService.instance?.refreshBubble()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notify(state: Dictation.State) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
    }

    private fun notification(state: Dictation.State): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = state.message ?: when (state.phase) {
            Dictation.Phase.Loading -> "Loading the speech model…"
            Dictation.Phase.Recording -> "Listening…"
            Dictation.Phase.Transcribing -> "Transcribing…"
            else -> "Tap the mic key, or hold it to talk"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_hex)
            .setContentTitle("Hex is on")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Paste last", action(ACTION_PASTE_LAST, 1)).build())
            .addAction(Notification.Action.Builder(null, "Turn off", action(ACTION_OFF, 2)).build())
            .build()
    }

    private fun action(name: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, DictationService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL = "hex"
        private const val NOTIFICATION = 1
        const val ACTION_OFF = "app.hexforandroid.OFF"
        const val ACTION_PASTE_LAST = "app.hexforandroid.PASTE_LAST"

        @Volatile var running = false
            private set

        /** Returns false if Android refused to start it (only allowed while Hex is in front). */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, DictationService::class.java))
            true
        } catch (e: Exception) {
            android.util.Log.w("HexService", "start refused", e)
            false
        }

        fun stop(context: Context) {
            Settings(context).enabled = false
            context.stopService(Intent(context, DictationService::class.java))
        }
    }
}
