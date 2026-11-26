package com.aisling.somawatch

import android.content.Context
import org.json.JSONObject
import kotlin.math.roundToInt

data class SyncedBiometricsSummary(
    val timestamp: Long = 0L,
    val durationSec: Int = 0,
    val avgHr: Int = 0,
    val peakHr: Int = 0,
    val hrvMs: Int = -1,
    val hrr1: Int = -1,
    val hrr2: Int = -1,
    val sessionZ1Sec: Int = 0,
    val sessionZ2Sec: Int = 0,
    val sessionZ3Sec: Int = 0,
    val sessionVigSec: Int = 0,
    val sessionCardioLoadMin: Int = 0,
    val weeklyZ2Min: Int = 0,
    val weeklyZ3Min: Int = 0,
    val weeklyZ2Goal: Int = 150,
    val weeklyVigMin: Int = 0,
    val weeklyVigGoal: Int = 75,
    val weeklyCardioLoadMin: Int = 0,
    val weeklyCardioLoadGoal: Int = 300
) {
    val weeklyAerobicMin: Int get() = weeklyZ2Min + weeklyZ3Min
}

object MobileBiometricsStore {
    private const val PREFS = "soma_mobile_bio_v1"

    fun computeCardioLoadMinutes(z2Sec: Long, z3Sec: Long, vigSec: Long): Int {
        val weightedSec = z2Sec * 1.0 + z3Sec * 1.5 + vigSec * 2.0
        return (weightedSec / 60.0).roundToInt()
    }

    fun saveWorkoutSummary(context: Context, jsonStr: String): SyncedBiometricsSummary? {
        return runCatching {
            val j = JSONObject(jsonStr)
            val sZ2 = j.optInt("sessionZ2Sec", 0)
            val sZ3 = j.optInt("sessionZ3Sec", 0)
            val sVig = j.optInt("sessionVigSec", 0)
            val wZ2 = j.optInt("weeklyZ2Min", 0)
            val wZ3 = j.optInt("weeklyZ3Min", 0)
            val wZ2Goal = j.optInt("weeklyZ2Goal", 150)
            val wVig = j.optInt("weeklyVigMin", 0)
            val wVigGoal = j.optInt("weeklyVigGoal", 75)
            val fallbackSessionLoad = computeCardioLoadMinutes(sZ2.toLong(), sZ3.toLong(), sVig.toLong())
            val fallbackWeeklyLoad = (wZ2 * 1.0 + wZ3 * 1.5 + wVig * 2.0).roundToInt()
            val summary = SyncedBiometricsSummary(
                timestamp = j.optLong("timestamp", System.currentTimeMillis()),
                durationSec = j.optInt("durationSec", 0),
                avgHr = j.optInt("avgHr", 0),
                peakHr = j.optInt("peakHr", 0),
                hrvMs = j.optInt("hrvMs", -1),
                hrr1 = j.optInt("hrr1", -1),
                hrr2 = j.optInt("hrr2", -1),
                sessionZ1Sec = j.optInt("sessionZ1Sec", 0),
                sessionZ2Sec = sZ2,
                sessionZ3Sec = sZ3,
                sessionVigSec = sVig,
                sessionCardioLoadMin = j.optInt("sessionCardioLoadMin", fallbackSessionLoad),
                weeklyZ2Min = wZ2,
                weeklyZ3Min = wZ3,
                weeklyZ2Goal = wZ2Goal,
                weeklyVigMin = wVig,
                weeklyVigGoal = wVigGoal,
                weeklyCardioLoadMin = j.optInt("weeklyCardioLoadMin", fallbackWeeklyLoad),
                weeklyCardioLoadGoal = j.optInt("weeklyCardioLoadGoal", wZ2Goal + wVigGoal * 2)
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

    fun saveGoals(context: Context, maxHr: Int, z2Goal: Int, vigGoal: Int, noiseDb: Int = 85) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("max_hr", maxHr)
            .putInt("z2_goal", z2Goal)
            .putInt("vig_goal", vigGoal)
            .putInt("noise_db", noiseDb.coerceIn(60, 110))
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

    fun loadNoiseThreshold(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("noise_db", 85)
}
