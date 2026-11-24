package com.aisling.somawatch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HealthDbStats(
    val workoutCount: Int = 0,
    val sleepCount: Int = 0,
    val hrvDaysCount: Int = 0,
    val hrrEventsCount: Int = 0,
    val noiseEventsCount: Int = 0,
    val telemetryCount: Int = 0,
    val latestSleepSummary: String? = null,
    val avgHrv7d: Int? = null,
    val avgHrv30d: Int? = null
)

class SomaHealthDb(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE workouts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                duration_sec INTEGER NOT NULL,
                avg_hr INTEGER NOT NULL,
                peak_hr INTEGER NOT NULL,
                hrv_ms INTEGER,
                hrr1 INTEGER,
                hrr2 INTEGER,
                z2_sec INTEGER NOT NULL,
                z3_sec INTEGER NOT NULL,
                vig_sec INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE telemetry_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                hr INTEGER NOT NULL,
                hrv_ms INTEGER,
                zone INTEGER NOT NULL,
                noise_db INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE hrr_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                source TEXT NOT NULL,
                peak_hr INTEGER NOT NULL,
                hrr1 INTEGER,
                hrr2 INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE noise_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                db INTEGER NOT NULL,
                threshold_db INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE sleep_sessions (
                start_ms INTEGER PRIMARY KEY,
                end_ms INTEGER NOT NULL,
                duration_min INTEGER NOT NULL,
                avg_hr INTEGER,
                min_hr INTEGER,
                avg_hrv_ms INTEGER,
                deep_min INTEGER NOT NULL,
                rem_min INTEGER NOT NULL,
                light_min INTEGER NOT NULL,
                awake_min INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE daily_hrv (
                day_iso TEXT PRIMARY KEY,
                avg_hrv_ms INTEGER NOT NULL,
                min_hrv_ms INTEGER NOT NULL,
                max_hrv_ms INTEGER NOT NULL,
                sample_count INTEGER NOT NULL
            )
            """.trimIndent()
        )
        createRecoveryBoutsTable(db)
    }

    private fun createRecoveryBoutsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS recovery_bouts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                peak_hr INTEGER NOT NULL,
                end_hr INTEGER NOT NULL,
                drop_bpm INTEGER NOT NULL,
                observed_sec INTEGER NOT NULL,
                tau_sec INTEGER NOT NULL,
                slope_bpm_min INTEGER NOT NULL,
                projected_hrr60 INTEGER NOT NULL,
                confidence TEXT NOT NULL,
                rest_type TEXT NOT NULL,
                context TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createRecoveryBoutsTable(db)
        }
    }

    fun insertWorkout(s: SyncedBiometricsSummary, z3Sec: Int = 0) {
        val cv = ContentValues().apply {
            put("timestamp", s.timestamp)
            put("duration_sec", s.durationSec)
            put("avg_hr", s.avgHr)
            put("peak_hr", s.peakHr)
            if (s.hrvMs > 0) put("hrv_ms", s.hrvMs)
            if (s.hrr1 >= 0) put("hrr1", s.hrr1)
            if (s.hrr2 >= 0) put("hrr2", s.hrr2)
            put("z2_sec", s.sessionZ2Sec)
            put("z3_sec", z3Sec)
            put("vig_sec", s.sessionVigSec)
        }
        writableDatabase.insert("workouts", null, cv)
        if (s.hrr1 >= 0 || s.hrr2 >= 0) {
            insertHrrEvent(s.timestamp, "workout", s.peakHr, s.hrr1.takeIf { it >= 0 }, s.hrr2.takeIf { it >= 0 })
        }
    }

    fun insertTelemetry(timestamp: Long, hr: Int, hrvMs: Int?, zone: Int, noiseDb: Int?) {
        val cv = ContentValues().apply {
            put("timestamp", timestamp)
            put("hr", hr)
            if (hrvMs != null && hrvMs > 0) put("hrv_ms", hrvMs)
            put("zone", zone)
            if (noiseDb != null && noiseDb > 0) put("noise_db", noiseDb)
        }
        writableDatabase.insert("telemetry_samples", null, cv)

        if (hrvMs != null && hrvMs > 0) {
            val dayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
            upsertDailyHrvSample(dayIso, hrvMs)
        }
    }

    fun insertHrrEvent(timestamp: Long, source: String, peakHr: Int, hrr1: Int?, hrr2: Int?) {
        val cv = ContentValues().apply {
            put("timestamp", timestamp)
            put("source", source)
            put("peak_hr", peakHr)
            if (hrr1 != null) put("hrr1", hrr1)
            if (hrr2 != null) put("hrr2", hrr2)
        }
        writableDatabase.insert("hrr_events", null, cv)
    }

    fun insertRecoveryBout(
        timestamp: Long,
        peakHr: Int,
        endHr: Int,
        dropBpm: Int,
        observedSec: Int,
        tauSec: Int,
        slopeBpmMin: Int,
        projectedHrr60: Int,
        confidence: String,
        restType: String,
        context: String
    ) {
        val cv = ContentValues().apply {
            put("timestamp", timestamp)
            put("peak_hr", peakHr)
            put("end_hr", endHr)
            put("drop_bpm", dropBpm)
            put("observed_sec", observedSec)
            put("tau_sec", tauSec)
            put("slope_bpm_min", slopeBpmMin)
            put("projected_hrr60", projectedHrr60)
            put("confidence", confidence)
            put("rest_type", restType)
            put("context", context)
        }
        writableDatabase.insert("recovery_bouts", null, cv)
    }

    fun insertNoiseEvent(timestamp: Long, dbVal: Int, thresholdDb: Int) {
        val cv = ContentValues().apply {
            put("timestamp", timestamp)
            put("db", dbVal)
            put("threshold_db", thresholdDb)
        }
        writableDatabase.insert("noise_events", null, cv)
    }

    fun upsertSleepSession(
        startMs: Long,
        endMs: Long,
        durationMin: Int,
        avgHr: Int?,
        minHr: Int?,
        avgHrvMs: Int?,
        deepMin: Int,
        remMin: Int,
        lightMin: Int,
        awakeMin: Int
    ) {
        val cv = ContentValues().apply {
            put("start_ms", startMs)
            put("end_ms", endMs)
            put("duration_min", durationMin)
            if (avgHr != null) put("avg_hr", avgHr)
            if (minHr != null) put("min_hr", minHr)
            if (avgHrvMs != null) put("avg_hrv_ms", avgHrvMs)
            put("deep_min", deepMin)
            put("rem_min", remMin)
            put("light_min", lightMin)
            put("awake_min", awakeMin)
        }
        writableDatabase.insertWithOnConflict("sleep_sessions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertDailyHrv(dayIso: String, avgMs: Int, minMs: Int, maxMs: Int, count: Int) {
        val cv = ContentValues().apply {
            put("day_iso", dayIso)
            put("avg_hrv_ms", avgMs)
            put("min_hrv_ms", minMs)
            put("max_hrv_ms", maxMs)
            put("sample_count", count)
        }
        writableDatabase.insertWithOnConflict("daily_hrv", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun upsertDailyHrvSample(dayIso: String, hrvMs: Int) {
        val c = readableDatabase.rawQuery(
            "SELECT avg_hrv_ms, min_hrv_ms, max_hrv_ms, sample_count FROM daily_hrv WHERE day_iso = ?",
            arrayOf(dayIso)
        )
        c.use {
            if (it.moveToFirst()) {
                val oldAvg = it.getInt(0)
                val oldMin = it.getInt(1)
                val oldMax = it.getInt(2)
                val oldCnt = it.getInt(3)
                val newCnt = oldCnt + 1
                val newAvg = ((oldAvg * oldCnt) + hrvMs) / newCnt
                upsertDailyHrv(dayIso, newAvg, minOf(oldMin, hrvMs), maxOf(oldMax, hrvMs), newCnt)
            } else {
                upsertDailyHrv(dayIso, hrvMs, hrvMs, hrvMs, 1)
            }
        }
    }

    fun loadStats(): HealthDbStats {
        val db = readableDatabase
        fun count(table: String): Int {
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                return if (c.moveToFirst()) c.getInt(0) else 0
            }
        }

        var latestSleep: String? = null
        db.rawQuery(
            "SELECT start_ms, duration_min, avg_hr, avg_hrv_ms, deep_min, rem_min FROM sleep_sessions ORDER BY start_ms DESC LIMIT 1",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                val dateStr = SimpleDateFormat("MMM dd", Locale.US).format(Date(c.getLong(0)))
                val durMin = c.getInt(1)
                val hrs = durMin / 60
                val mins = durMin % 60
                val hrPart = if (!c.isNull(2)) " • HR ${c.getInt(2)}" else ""
                val hrvPart = if (!c.isNull(3)) " • HRV ${c.getInt(3)}ms" else ""
                latestSleep = "$dateStr: ${hrs}h ${mins}m$hrPart$hrvPart"
            }
        }

        fun avgHrvLastDays(days: Int): Int? {
            db.rawQuery(
                "SELECT AVG(avg_hrv_ms) FROM (SELECT avg_hrv_ms FROM daily_hrv ORDER BY day_iso DESC LIMIT ?)",
                arrayOf(days.toString())
            ).use { c ->
                return if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0).toInt() else null
            }
        }

        return HealthDbStats(
            workoutCount = count("workouts"),
            sleepCount = count("sleep_sessions"),
            hrvDaysCount = count("daily_hrv"),
            hrrEventsCount = count("hrr_events"),
            noiseEventsCount = count("noise_events"),
            telemetryCount = count("telemetry_samples"),
            latestSleepSummary = latestSleep,
            avgHrv7d = avgHrvLastDays(7),
            avgHrv30d = avgHrvLastDays(30)
        )
    }

    fun buildFullExportJson(context: Context): JSONObject {
        val db = readableDatabase
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

        val root = JSONObject()
        root.put("schema", "soma-watch-health-export/v1")
        root.put("exported_at", isoFmt.format(Date()))
        root.put("app_version", BuildConfig.VERSION_NAME)

        val goals = MobileBiometricsStore.loadGoals(context)
        root.put("goals", JSONObject().apply {
            put("max_hr", goals.first)
            put("weekly_zone2_goal_min", goals.second)
            put("weekly_vigorous_goal_min", goals.third)
        })

        val stats = loadStats()
        root.put("summary", JSONObject().apply {
            put("workouts_total", stats.workoutCount)
            put("sleep_sessions_total", stats.sleepCount)
            put("hrv_days_tracked", stats.hrvDaysCount)
            put("avg_hrv_7d_ms", stats.avgHrv7d ?: JSONObject.NULL)
            put("avg_hrv_30d_ms", stats.avgHrv30d ?: JSONObject.NULL)
            put("hrr_events_total", stats.hrrEventsCount)
            put("noise_alerts_total", stats.noiseEventsCount)
        })

        val dailyHrvArr = JSONArray()
        db.rawQuery("SELECT day_iso, avg_hrv_ms, min_hrv_ms, max_hrv_ms, sample_count FROM daily_hrv ORDER BY day_iso DESC LIMIT 365", null).use { c ->
            while (c.moveToNext()) {
                dailyHrvArr.put(JSONObject().apply {
                    put("day", c.getString(0))
                    put("avg_hrv_ms", c.getInt(1))
                    put("min_hrv_ms", c.getInt(2))
                    put("max_hrv_ms", c.getInt(3))
                    put("samples", c.getInt(4))
                })
            }
        }
        root.put("daily_hrv_trend", dailyHrvArr)

        val sleepArr = JSONArray()
        db.rawQuery("SELECT start_ms, end_ms, duration_min, avg_hr, min_hr, avg_hrv_ms, deep_min, rem_min, light_min, awake_min FROM sleep_sessions ORDER BY start_ms DESC LIMIT 90", null).use { c ->
            while (c.moveToNext()) {
                sleepArr.put(JSONObject().apply {
                    put("start", isoFmt.format(Date(c.getLong(0))))
                    put("end", isoFmt.format(Date(c.getLong(1))))
                    put("duration_min", c.getInt(2))
                    put("avg_hr", if (c.isNull(3)) JSONObject.NULL else c.getInt(3))
                    put("min_hr", if (c.isNull(4)) JSONObject.NULL else c.getInt(4))
                    put("avg_hrv_ms", if (c.isNull(5)) JSONObject.NULL else c.getInt(5))
                    put("deep_min", c.getInt(6))
                    put("rem_min", c.getInt(7))
                    put("light_min", c.getInt(8))
                    put("awake_min", c.getInt(9))
                })
            }
        }
        root.put("sleep_sessions", sleepArr)

        val workoutsArr = JSONArray()
        db.rawQuery("SELECT timestamp, duration_sec, avg_hr, peak_hr, hrv_ms, hrr1, hrr2, z2_sec, z3_sec, vig_sec FROM workouts ORDER BY timestamp DESC LIMIT 200", null).use { c ->
            while (c.moveToNext()) {
                workoutsArr.put(JSONObject().apply {
                    put("timestamp", isoFmt.format(Date(c.getLong(0))))
                    put("duration_sec", c.getInt(1))
                    put("avg_hr", c.getInt(2))
                    put("peak_hr", c.getInt(3))
                    put("hrv_ms", if (c.isNull(4)) JSONObject.NULL else c.getInt(4))
                    put("hrr1_bpm_drop", if (c.isNull(5)) JSONObject.NULL else c.getInt(5))
                    put("hrr2_bpm_drop", if (c.isNull(6)) JSONObject.NULL else c.getInt(6))
                    put("zone2_sec", c.getInt(7))
                    put("zone3_sec", c.getInt(8))
                    put("vigorous_sec", c.getInt(9))
                })
            }
        }
        root.put("workouts", workoutsArr)

        val hrrArr = JSONArray()
        db.rawQuery("SELECT timestamp, source, peak_hr, hrr1, hrr2 FROM hrr_events ORDER BY timestamp DESC LIMIT 200", null).use { c ->
            while (c.moveToNext()) {
                hrrArr.put(JSONObject().apply {
                    put("timestamp", isoFmt.format(Date(c.getLong(0))))
                    put("source", c.getString(1))
                    put("peak_hr", c.getInt(2))
                    put("hrr1_drop", if (c.isNull(3)) JSONObject.NULL else c.getInt(3))
                    put("hrr2_drop", if (c.isNull(4)) JSONObject.NULL else c.getInt(4))
                })
            }
        }
        root.put("hrr_events", hrrArr)

        val boutsArr = JSONArray()
        db.rawQuery(
            "SELECT timestamp, peak_hr, end_hr, drop_bpm, observed_sec, tau_sec, slope_bpm_min, projected_hrr60, confidence, rest_type, context FROM recovery_bouts ORDER BY timestamp DESC LIMIT 200",
            null
        ).use { c ->
            while (c.moveToNext()) {
                boutsArr.put(JSONObject().apply {
                    put("timestamp", isoFmt.format(Date(c.getLong(0))))
                    put("peak_hr", c.getInt(1))
                    put("end_hr", c.getInt(2))
                    put("drop_bpm", c.getInt(3))
                    put("observed_sec", c.getInt(4))
                    put("tau_sec", c.getInt(5))
                    put("slope_bpm_per_min", c.getInt(6))
                    put("projected_hrr60", c.getInt(7))
                    put("confidence", c.getString(8))
                    put("rest_type", c.getString(9))
                    put("context", c.getString(10))
                })
            }
        }
        root.put("continuous_recovery_bouts", boutsArr)

        val noiseArr = JSONArray()
        db.rawQuery("SELECT timestamp, db, threshold_db FROM noise_events ORDER BY timestamp DESC LIMIT 200", null).use { c ->
            while (c.moveToNext()) {
                noiseArr.put(JSONObject().apply {
                    put("timestamp", isoFmt.format(Date(c.getLong(0))))
                    put("db", c.getInt(1))
                    put("threshold_db", c.getInt(2))
                })
            }
        }
        root.put("noise_alerts", noiseArr)

        val telArr = JSONArray()
        db.rawQuery("SELECT timestamp, hr, hrv_ms, zone, noise_db FROM telemetry_samples ORDER BY timestamp DESC LIMIT 500", null).use { c ->
            while (c.moveToNext()) {
                telArr.put(JSONObject().apply {
                    put("timestamp", isoFmt.format(Date(c.getLong(0))))
                    put("hr", c.getInt(1))
                    put("hrv_ms", if (c.isNull(2)) JSONObject.NULL else c.getInt(2))
                    put("zone", c.getInt(3))
                    put("noise_db", if (c.isNull(4)) JSONObject.NULL else c.getInt(4))
                })
            }
        }
        root.put("recent_telemetry", telArr)

        return root
    }

    fun buildClaudeMarkdownDigest(context: Context): String {
        val stats = loadStats()
        val export = buildFullExportJson(context)
        return buildString {
            appendLine("# Soma Watch — Health Telemetry Digest")
            appendLine("Exported: ${export.optString("exported_at")}")
            appendLine()
            appendLine("## Key Biomarkers")
            appendLine("- **HRV 7-day avg:** ${stats.avgHrv7d?.let { "$it ms" } ?: "n/a"} | **30-day avg:** ${stats.avgHrv30d?.let { "$it ms" } ?: "n/a"}")
            appendLine("- **Latest Sleep:** ${stats.latestSleepSummary ?: "no sleep data synced yet"}")
            appendLine("- **Recorded Workouts:** ${stats.workoutCount} | **HRR Events:** ${stats.hrrEventsCount} | **Noise Alerts:** ${stats.noiseEventsCount}")
            appendLine()
            appendLine("## Raw JSON Payload")
            appendLine("```json")
            appendLine(export.toString(2))
            appendLine("```")
        }
    }

    fun writeExportFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val outFile = File(dir, "soma_health_export.json")
        outFile.writeText(buildFullExportJson(context).toString(2), Charsets.UTF_8)
        return outFile
    }

    companion object {
        private const val DB_NAME = "soma_health.db"
        private const val DB_VERSION = 2

        @Volatile
        private var instance: SomaHealthDb? = null

        fun get(context: Context): SomaHealthDb =
            instance ?: synchronized(this) {
                instance ?: SomaHealthDb(context).also { instance = it }
            }
    }
}
