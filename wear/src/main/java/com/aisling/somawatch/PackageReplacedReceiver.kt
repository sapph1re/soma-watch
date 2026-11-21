package com.aisling.somawatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i("SomaWatchUpdate", "Package replaced -> relaunching WearMainActivity (v${BuildConfig.VERSION_NAME})")

        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 80, 70, 120), -1)
            )
        }

        val launchIntent = Intent(context, WearMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("just_updated", true)
        }
        runCatching { context.startActivity(launchIntent) }
            .onFailure { Log.w("SomaWatchUpdate", "Could not auto-relaunch activity: ${it.localizedMessage}") }
    }
}
