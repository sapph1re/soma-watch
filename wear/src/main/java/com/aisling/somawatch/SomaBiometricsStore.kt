package com.aisling.somawatch

import android.content.Context
import java.util.Calendar
import kotlin.math.roundToInt

data class WeeklyCardioStats(
    val weekKey: String,
    val zone2Seconds: Long,
    val zone3Seconds: Long,
    val vigorousSeconds: Long, // Zone 4 + Zone 5
    val zone2GoalMin: Int,
    val vigorousGoalMin: Int,
    val maxHr: Int,
    val lastHrr1: Int?,
    val lastHrr2: Int?,
    val lastTauSec: Int?,
    val lastProjectedHrr60: Int?,
    val lastHrvMs: Int?
) {
    val zone2Minutes: Int get() = (zone2Seconds / 60.0).roundToInt()
    val zone3Minutes: Int get() = (zone3Seconds / 60.0).roundToInt()
    val vigorousMinutes: Int get() = (vigorousSeconds / 60.0).roundToInt()
}

object SomaBiometricsStore {
    private const val PREFS = "soma_biometrics_v1"

    private fun currentWeekKey(): String {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        val year = cal.get(Calendar.YEAR)
        val week = cal.get(Calendar.WEEK_OF_YEAR)
        return "$year-W$week"
    }

    fun load(context: Context): WeeklyCardioStats {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nowWeek = currentWeekKey()
        val savedWeek = prefs.getString("week_key", nowWeek) ?: nowWeek

        if (savedWeek != nowWeek) {
            prefs.edit()
                .putString("week_key", nowWeek)
                .putLong("z2_sec", 0L)
                .putLong("z3_sec", 0L)
                .putLong("vig_sec", 0L)
                .apply()
        }

        val hrr1 = prefs.getInt("last_hrr1", -1).takeIf { it >= 0 }
        val hrr2 = prefs.getInt("last_hrr2", -1).takeIf { it >= 0 }
        val tau = prefs.getInt("last_tau_sec", -1).takeIf { it > 0 }
        val projHrr = prefs.getInt("last_proj_hrr60", -1).takeIf { it > 0 }
        val hrv = prefs.getInt("last_hrv", -1).takeIf { it > 0 }

        return WeeklyCardioStats(
            weekKey = nowWeek,
            zone2Seconds = prefs.getLong("z2_sec", 0L),
            zone3Seconds = prefs.getLong("z3_sec", 0L),
            vigorousSeconds = prefs.getLong("vig_sec", 0L),
            zone2GoalMin = prefs.getInt("z2_goal_min", 150),
            vigorousGoalMin = prefs.getInt("vig_goal_min", 75),
            maxHr = prefs.getInt("max_hr", 185),
            lastHrr1 = hrr1,
            lastHrr2 = hrr2,
            lastTauSec = tau,
            lastProjectedHrr60 = projHrr,
            lastHrvMs = hrv
        )
    }

    fun addZoneSeconds(context: Context, zone: Int, seconds: Long) {
        if (seconds <= 0L) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nowWeek = currentWeekKey()
        val sameWeek = prefs.getString("week_key", "") == nowWeek
        val editor = prefs.edit()
        if (!sameWeek) {
            editor.putString("week_key", nowWeek)
                .putLong("z2_sec", 0L)
                .putLong("z3_sec", 0L)
                .putLong("vig_sec", 0L)
        }
        val baseZ2 = if (sameWeek) prefs.getLong("z2_sec", 0L) else 0L
        val baseZ3 = if (sameWeek) prefs.getLong("z3_sec", 0L) else 0L
        val baseVig = if (sameWeek) prefs.getLong("vig_sec", 0L) else 0L
        when (zone) {
            2 -> editor.putLong("z2_sec", baseZ2 + seconds)
            3 -> editor.putLong("z3_sec", baseZ3 + seconds)
            4, 5 -> editor.putLong("vig_sec", baseVig + seconds)
        }
        editor.apply()
    }

    fun saveLastHrr(context: Context, hrr1: Int?, hrr2: Int?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (hrr1 != null) editor.putInt("last_hrr1", hrr1)
        if (hrr2 != null) editor.putInt("last_hrr2", hrr2)
        editor.apply()
    }

    fun saveRecoveryBout(context: Context, bout: RecoveryBout) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putInt("last_tau_sec", bout.tauSec)
            .putInt("last_proj_hrr60", bout.projectedHrr60)
        if (bout.confidence == "measured_60s") {
            editor.putInt("last_hrr1", bout.projectedHrr60)
        }
        editor.apply()
    }

    fun saveLastHrv(context: Context, hrvMs: Int) {
        if (hrvMs <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt("last_hrv", hrvMs).apply()
    }

    fun updateGoals(context: Context, maxHr: Int, z2GoalMin: Int, vigGoalMin: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("max_hr", maxHr.coerceIn(140, 220))
            .putInt("z2_goal_min", z2GoalMin.coerceIn(30, 600))
            .putInt("vig_goal_min", vigGoalMin.coerceIn(15, 300))
            .apply()
    }

    fun getNoiseThresholdDb(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("noise_threshold_db", 85)

    fun setNoiseThresholdDb(context: Context, db: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("noise_threshold_db", db.coerceIn(60, 110)).apply()
    }

    fun isNoiseAlertEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("noise_alert_enabled", false)

    fun setNoiseAlertEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("noise_alert_enabled", enabled).apply()
    }

    fun isAutoDetectEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_detect_enabled", true)

    fun setAutoDetectEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("auto_detect_enabled", enabled).apply()
    }

    private fun currentDayKey(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
    }

    fun saveNoiseReading(context: Context, db: Int, timestampMs: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = currentDayKey()
        val savedDay = prefs.getString("peak_noise_day", "")
        val prevPeak = if (savedDay == today) prefs.getInt("peak_noise_db", 0) else 0
        val newPeak = maxOf(prevPeak, db)
        prefs.edit()
            .putInt("last_noise_db", db)
            .putLong("last_noise_ts", timestampMs)
            .putString("peak_noise_day", today)
            .putInt("peak_noise_db", newPeak)
            .apply()
    }

    fun getLastNoiseDb(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("last_noise_db", 0)

    fun getLastNoiseTimestamp(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_noise_ts", 0L)

    fun getPeakNoiseDbToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getString("peak_noise_day", "") == currentDayKey()) {
            prefs.getInt("peak_noise_db", 0)
        } else {
            0
        }
    }
}
