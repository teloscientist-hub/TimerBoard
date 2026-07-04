package com.mark.timerboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlin.math.max

class TimerForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var runningCount = 0
    private var nextTimerName = ""
    private var nextTimerEndWallTimeMillis = 0L

    private val updateNotification = object : Runnable {
        override fun run() {
            if (runningCount > 0) {
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTimerService()
            ACTION_PAUSE_ALL -> {
                if (!TimerCommandBus.pauseAll()) stopTimerService()
            }
            ACTION_RESET_ALL -> {
                if (!TimerCommandBus.resetAll()) stopTimerService()
            }
            ACTION_START_OR_UPDATE -> {
                runningCount = intent.getIntExtra(EXTRA_RUNNING_COUNT, 0)
                nextTimerName = intent.getStringExtra(EXTRA_NEXT_TIMER_NAME).orEmpty()
                nextTimerEndWallTimeMillis = intent.getLongExtra(EXTRA_NEXT_END_WALL_TIME, 0L)

                if (runningCount <= 0) {
                    stopTimerService()
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    handler.removeCallbacks(updateNotification)
                    handler.postDelayed(updateNotification, 1_000L)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun stopTimerService() {
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val remainingMillis = max(0L, nextTimerEndWallTimeMillis - System.currentTimeMillis())
        val title = if (runningCount == 1) {
            "Timer running"
        } else {
            "$runningCount timers running"
        }
        val timerName = nextTimerName.ifBlank { "Next timer" }
        val text = "$timerName ends at ${timeFormatter.format(java.util.Date(nextTimerEndWallTimeMillis))}"
        val detail = "${remainingMillis.formatTimer()} remaining"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$detail"))
            .setContentIntent(openAppIntent())
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause all",
                serviceActionIntent(ACTION_PAUSE_ALL, 1)
            )
            .addAction(
                android.R.drawable.ic_menu_revert,
                "Reset all",
                serviceActionIntent(ACTION_RESET_ALL, 2)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TimerForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Running timers",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active TimerBoard timers"
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "running_timers"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START_OR_UPDATE = "com.mark.timerboard.START_OR_UPDATE"
        private const val ACTION_STOP = "com.mark.timerboard.STOP"
        private const val ACTION_PAUSE_ALL = "com.mark.timerboard.PAUSE_ALL"
        private const val ACTION_RESET_ALL = "com.mark.timerboard.RESET_ALL"
        private const val EXTRA_RUNNING_COUNT = "running_count"
        private const val EXTRA_NEXT_TIMER_NAME = "next_timer_name"
        private const val EXTRA_NEXT_END_WALL_TIME = "next_end_wall_time"

        fun sync(context: Context, timers: List<TimerItem>) {
            val runningTimers = timers.filter { it.isRunning }
            if (runningTimers.isEmpty()) {
                context.startService(Intent(context, TimerForegroundService::class.java).apply {
                    action = ACTION_STOP
                })
                return
            }

            val nextTimer = runningTimers.minBy { it.endElapsedRealtime }
            val nextEndWallTime = System.currentTimeMillis() +
                max(0L, nextTimer.endElapsedRealtime - android.os.SystemClock.elapsedRealtime())
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_RUNNING_COUNT, runningTimers.size)
                putExtra(EXTRA_NEXT_TIMER_NAME, nextTimer.preset.name)
                putExtra(EXTRA_NEXT_END_WALL_TIME, nextEndWallTime)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
