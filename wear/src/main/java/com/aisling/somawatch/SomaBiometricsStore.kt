package com.aisling.somawatch

import android.content.Context
import java.util.Calendar

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
    val lastHrvMs: Int?
) {
    val zone2Minutes: Int get() = (zone2Seconds / 60L).toInt()
    val zone3Minutes: Int get() = (zone3Seconds / 60L).toInt()
    val vigorousMinutes: Int get() = (vigorousSeconds / 60L).toInt()
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
            lastHrvMs = hrv
        )
    }

    fun addZoneSecond(context: Context, zone: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nowWeek = currentWeekKey()
        val editor = prefs.edit()
        if (prefs.getString("week_key", "") != nowWeek) {
            editor.putString("week_key", nowWeek)
                .putLong("z2_sec", 0L)
                .putLong("z3_sec", 0L)
                .putLong("vig_sec", 0L)
        }
        when (zone) {
            2 -> editor.putLong("z2_sec", prefs.getLong("z2_sec", 0L) + 1L)
            3 -> editor.putLong("z3_sec", prefs.getLong("z3_sec", 0L) + 1L)
            4, 5 -> editor.putLong("vig_sec", prefs.getLong("vig_sec", 0L) + 1L)
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
}
