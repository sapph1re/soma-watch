package com.aisling.somawatch

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object HealthConnectManager {
    private const val TAG = "SomaHealthConnect"

    val REQUIRED_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    )

    fun isAvailable(context: Context): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun hasAllPermissions(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) return@withContext false
        runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(REQUIRED_PERMISSIONS)
        }.getOrDefault(false)
    }

    suspend fun syncLast30Days(context: Context): Result<String> = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) {
            return@withContext Result.failure(IllegalStateException("Health Connect не установлен на телефоне"))
        }

        try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.none { it in REQUIRED_PERMISSIONS }) {
                return@withContext Result.failure(IllegalStateException("Нужны разрешения Health Connect"))
            }

            val now = Instant.now()
            val start30d = now.minus(30, ChronoUnit.DAYS)
            val db = SomaHealthDb.get(context)
            val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

            // 1. Read HRV RMSSD records for last 30 days & aggregate by day
            val hrvRecords = runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateVariabilityRmssdRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start30d, now)
                    )
                ).records
            }.getOrDefault(emptyList())

            val hrvByDay = hrvRecords.groupBy { dayFmt.format(Date.from(it.time)) }
            hrvByDay.forEach { (day, list) ->
                val values = list.map { it.heartRateVariabilityMillis.roundToInt() }.filter { it > 0 }
                if (values.isNotEmpty()) {
                    db.upsertDailyHrv(
                        dayIso = day,
                        avgMs = values.average().roundToInt(),
                        minMs = values.min(),
                        maxMs = values.max(),
                        count = values.size
                    )
                }
            }

            // 2. Read Sleep Sessions for last 30 days and correlate with Sleep HR & Sleep HRV
            val sleepRecords = runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start30d, now)
                    )
                ).records
            }.getOrDefault(emptyList())

            for (session in sleepRecords) {
                val startMs = session.startTime.toEpochMilli()
                val endMs = session.endTime.toEpochMilli()
                val durationMin = ((endMs - startMs) / 60_000L).toInt().coerceAtLeast(1)

                var deepMin = 0
                var remMin = 0
                var lightMin = 0
                var awakeMin = 0
                for (stage in session.stages) {
                    val mins = ((stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()) / 60_000L).toInt()
                    when (stage.stage) {
                        SleepSessionRecord.STAGE_TYPE_DEEP -> deepMin += mins
                        SleepSessionRecord.STAGE_TYPE_REM -> remMin += mins
                        SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> lightMin += mins
                        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> awakeMin += mins
                    }
                }

                // Query Heart Rate samples inside this sleep window
                val hrSamples = runCatching {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
                        )
                    ).records.flatMap { it.samples }.map { it.beatsPerMinute.toInt() }.filter { it in 30..200 }
                }.getOrDefault(emptyList())

                val sleepHrvSamples = hrvRecords
                    .filter { !it.time.isBefore(session.startTime) && !it.time.isAfter(session.endTime) }
                    .map { it.heartRateVariabilityMillis.roundToInt() }
                    .filter { it > 0 }

                db.upsertSleepSession(
                    startMs = startMs,
                    endMs = endMs,
                    durationMin = durationMin,
                    avgHr = if (hrSamples.isNotEmpty()) hrSamples.average().roundToInt() else null,
                    minHr = hrSamples.minOrNull(),
                    avgHrvMs = if (sleepHrvSamples.isNotEmpty()) sleepHrvSamples.average().roundToInt() else null,
                    deepMin = deepMin,
                    remMin = remMin,
                    lightMin = lightMin,
                    awakeMin = awakeMin
                )
            }

            // Refresh export JSON file automatically for ADB/Claude Code pulling
            db.writeExportFile(context)

            val msg = "Health Connect: ${sleepRecords.size} ночей сна, ${hrvRecords.size} замеров HRV (30д)"
            Log.i(TAG, msg)
            Result.success(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Health Connect sync error", e)
            Result.failure(e)
        }
    }
}
