package com.aisling.somawatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

class WorkoutForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireCpuWakeLock()
        ensureChannel()
        val notification = buildOngoingNotification(hr = 0, zone = 0, elapsedSec = 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseCpuWakeLock()
        super.onDestroy()
    }

    private fun acquireCpuWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = wakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SomaWatch:WorkoutCpuWakeLock"
            ).apply {
                setReferenceCounted(false)
                wakeLock = this
            }
            if (!wl.isHeld) {
                wl.acquire(4 * 60 * 60 * 1000L) // 4h safety ceiling
            }
        }
    }

    private fun releaseCpuWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Soma Тренировка",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Активный трекинг пульса, HRV и зон кардио"
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildOngoingNotification(hr: Int, zone: Int, elapsedSec: Int): Notification {
        val tapIntent = Intent(this, WearMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            2001,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeText = String.format("%02d:%02d", elapsedSec / 60, elapsedSec % 60)
        val statusLine = if (hr > 0) "♥ $hr • Z$zone • $timeText" else "Soma Бег • $timeText"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Soma Тренировка")
            .setContentText(statusLine)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)

        val ongoingStatus = Status.Builder()
            .addTemplate(statusLine)
            .build()

        val ongoingActivity = OngoingActivity.Builder(applicationContext, NOTIF_ID, builder)
            .setTouchIntent(pendingIntent)
            .setStatus(ongoingStatus)
            .build()

        ongoingActivity.apply(applicationContext)
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "soma_workout_channel"
        private const val NOTIF_ID = 7007

        fun start(context: Context) {
            val intent = Intent(context, WorkoutForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkoutForegroundService::class.java))
        }

        fun updateNotification(context: Context, hr: Int, zone: Int, elapsedSec: Int) {
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val tapIntent = Intent(context, WearMainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    2001,
                    tapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val timeText = String.format("%02d:%02d", elapsedSec / 60, elapsedSec % 60)
                val statusLine = if (hr > 0) "♥ $hr • Z$zone • $timeText" else "Soma Бег • $timeText"

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setContentTitle("Soma Тренировка")
                    .setContentText(statusLine)
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                    .setContentIntent(pendingIntent)

                val ongoingActivity = OngoingActivity.Builder(context.applicationContext, NOTIF_ID, builder)
                    .setTouchIntent(pendingIntent)
                    .setStatus(Status.Builder().addTemplate(statusLine).build())
                    .build()
                ongoingActivity.apply(context.applicationContext)
                nm.notify(NOTIF_ID, builder.build())
            }
        }
    }
}
