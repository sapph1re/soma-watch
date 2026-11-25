package com.aisling.somawatch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "Package replaced -> launching/notifying WearMainActivity (v${BuildConfig.VERSION_NAME})")

        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 80, 70, 140), -1)
            )
        }

        val launchIntent = Intent(context, WearMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("just_updated", true)
        }

        // Refresh watch face complications after OTA update
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                context.applicationContext,
                ComponentName(context.applicationContext, CardioZoneComplicationService::class.java)
            ).requestUpdateAll()
            ComplicationDataSourceUpdateRequester.create(
                context.applicationContext,
                ComponentName(context.applicationContext, CardioRingComplicationService::class.java)
            ).requestUpdateAll()
            ComplicationDataSourceUpdateRequester.create(
                context.applicationContext,
                ComponentName(context.applicationContext, NoiseComplicationService::class.java)
            ).requestUpdateAll()
        }

        // Try direct launch (succeeds if exempted from BAL)
        runCatching { context.startActivity(launchIntent) }
            .onFailure { Log.w(TAG, "Direct BAL launch restricted: ${it.localizedMessage}") }

        // Always post a high-priority Full-Screen Intent notification so Android 16 wakes Soma Watch immediately
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Soma Watch Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Opens Soma Watch immediately after an OTA update"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)

            val pendingIntent = PendingIntent.getActivity(
                context,
                1001,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle("Soma Watch Updated!")
                .setContentText("Version v${BuildConfig.VERSION_NAME} — tap to open")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .build()

            nm.notify(NOTIF_ID, notification)
        }.onFailure { Log.e(TAG, "Failed posting update notification", it) }
    }

    companion object {
        private const val TAG = "SomaWatchUpdate"
        private const val CHANNEL_ID = "soma_ota_updates"
        private const val NOTIF_ID = 8008
    }
}
