package com.aisling.somawatch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Lightweight background foreground service that keeps:
 * 1) Passive HR + linear acceleration monitoring alive for Auto-Cardio detection
 * 2) Periodic duty-cycled ambient dB sampling alive so the Noise watch-face complication never goes stale
 */
class SomaMonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasHealth = hasHealthFgsPermission(this)
        val hasMic = hasMicPermission(this)
        if (!hasHealth && !hasMic) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        val tapIntent = Intent(this, WearMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            2002,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusParts = buildList {
            if (hasHealth && SomaBiometricsStore.isAutoDetectEnabled(this@SomaMonitorService)) {
                add("Auto Cardio")
            }
            if (hasMic) {
                add("Noise dB")
            }
        }
        val subtitle = if (statusParts.isNotEmpty()) {
            statusParts.joinToString(" • ")
        } else {
            "Passive monitoring"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Soma Monitor")
            .setContentText(subtitle)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()

        val startedOk = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var fgsType = 0
                if (hasHealth) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                if (hasMic) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (fgsType == 0) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIF_ID, notification, fgsType)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        }.onFailure { err ->
            Log.w(TAG, "Failed startForeground on SomaMonitorService: ${err.localizedMessage}")
            stopSelf()
        }.getOrDefault(false)

        if (!startedOk) return START_NOT_STICKY

        isRunning = true
        if (hasHealth) {
            SomaBiometricsEngine.startPassiveMonitoring(applicationContext)
        }
        SomaNoiseMonitor.init(applicationContext)
        if (hasMic) {
            SomaNoiseMonitor.ensureBackgroundSampling(applicationContext)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Soma Background Monitor",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps Auto-Cardio detection and Noise watch face complication updated"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SomaMonitorService"
        private const val CHANNEL_ID = "soma_passive_monitor_channel"
        private const val NOTIF_ID = 7008

        @Volatile
        var isRunning: Boolean = false
            private set

        fun hasHealthFgsPermission(context: Context): Boolean {
            val bodyOk = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BODY_SENSORS
            ) == PackageManager.PERMISSION_GRANTED
            val activityOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
            val healthHrOk = if (Build.VERSION.SDK_INT >= 36) {
                ContextCompat.checkSelfPermission(
                    context,
                    "android.permission.health.READ_HEART_RATE"
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
            return bodyOk || activityOk || healthHrOk
        }

        fun hasMicPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            val ctx = context.applicationContext
            if (!hasHealthFgsPermission(ctx) && !hasMicPermission(ctx)) return
            runCatching {
                val intent = Intent(ctx, SomaMonitorService::class.java)
                ContextCompat.startForegroundService(ctx, intent)
            }.onFailure { err ->
                Log.w(TAG, "Unable to start SomaMonitorService: ${err.localizedMessage}")
            }
        }

        fun stop(context: Context) {
            isRunning = false
            runCatching {
                context.applicationContext.stopService(Intent(context.applicationContext, SomaMonitorService::class.java))
            }
        }
    }
}
