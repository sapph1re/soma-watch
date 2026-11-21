package com.aisling.somawatch

import android.content.Context
import org.json.JSONObject

data class SyncedBiometricsSummary(
    val timestamp: Long = 0L,
    val durationSec: Int = 0,
    val avgHr: Int = 0,
    val peakHr: Int = 0,
    val hrvMs: Int = -1,
    val hrr1: Int = -1,
    val hrr2: Int = -1,
    val sessionZ2Sec: Int = 0,
    val sessionVigSec: Int = 0,
    val weeklyZ2Min: Int = 0,
    val weeklyZ2Goal: Int = 150,
    val weeklyVigMin: Int = 0,
    val weeklyVigGoal: Int = 75
)

object MobileBiometricsStore {
    private const val PREFS = "soma_mobile_bio_v1"

    fun saveWorkoutSummary(context: Context, jsonStr: String): SyncedBiometricsSummary? {
        return runCatching {
            val j = JSONObject(jsonStr)
            val summary = SyncedBiometricsSummary(
                timestamp = j.optLong("timestamp", System.currentTimeMillis()),
                durationSec = j.optInt("durationSec", 0),
                avgHr = j.optInt("avgHr", 0),
                peakHr = j.optInt("peakHr", 0),
                hrvMs = j.optInt("hrvMs", -1),
                hrr1 = j.optInt("hrr1", -1),
                hrr2 = j.optInt("hrr2", -1),
                sessionZ2Sec = j.optInt("sessionZ2Sec", 0),
                sessionVigSec = j.optInt("sessionVigSec", 0),
                weeklyZ2Min = j.optInt("weeklyZ2Min", 0),
                weeklyZ2Goal = j.optInt("weeklyZ2Goal", 150),
                weeklyVigMin = j.optInt("weeklyVigMin", 0),
                weeklyVigGoal = j.optInt("weeklyVigGoal", 75)
            )
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("last_summary_json", jsonStr)
                .apply()
            summary
        }.getOrNull()
    }

    fun loadLastSummary(context: Context): SyncedBiometricsSummary? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("last_summary_json", null) ?: return null
        return saveWorkoutSummary(context, raw)
    }

    fun saveGoals(context: Context, maxHr: Int, z2Goal: Int, vigGoal: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("max_hr", maxHr)
            .putInt("z2_goal", z2Goal)
            .putInt("vig_goal", vigGoal)
            .apply()
    }

    fun loadGoals(context: Context): Triple<Int, Int, Int> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Triple(
            p.getInt("max_hr", 185),
            p.getInt("z2_goal", 150),
            p.getInt("vig_goal", 75)
        )
    }
}
