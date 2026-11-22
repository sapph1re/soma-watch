package com.aisling.somawatch

import android.content.ComponentName
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class WorkoutPhase {
    IDLE,
    ACTIVE,
    HRR_RECOVERY,
    SUMMARY
}

data class BiometricsUiState(
    val currentHr: Int = 0,
    val currentZone: Int = 0, // 0 = unknown, 1..5
    val currentHrvMs: Int? = null,
    val hrvSource: String = "IBI",
    val isSensorActive: Boolean = false,
    val phase: WorkoutPhase = WorkoutPhase.IDLE,
    val elapsedSeconds: Int = 0,
    val peakHr: Int = 0,
    val avgHr: Int = 0,
    val sessionZone2Sec: Int = 0,
    val sessionZone3Sec: Int = 0,
    val sessionVigorousSec: Int = 0,
    val hrHistory: List<Int> = emptyList(),
    // HRR recovery tracking
    val recoveryStartHr: Int = 0,
    val recoveryElapsedSec: Int = 0,
    val hrr1Drop: Int? = null,
    val hrr2Drop: Int? = null,
    // Auto-detected dynamic/strength workout prompt
    val autoWorkoutSuggestion: String? = null,
    val isMovingActively: Boolean = false,
    // Weekly persistent budget
    val weekly: WeeklyCardioStats = WeeklyCardioStats(
        weekKey = "",
        zone2Seconds = 0,
        zone3Seconds = 0,
        vigorousSeconds = 0,
        zone2GoalMin = 150,
        vigorousGoalMin = 75,
        maxHr = 185,
        lastHrr1 = null,
        lastHrr2 = null,
        lastHrvMs = null
    )
)

object SomaBiometricsEngine : SensorEventListener {
    private const val TAG = "SomaBiometrics"
    private const val SAMSUNG_HR_RAW_TYPE = 69682

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(BiometricsUiState())
    val state: StateFlow<BiometricsUiState> = _state

    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var tickerJob: Job? = null
    private var passiveJob: Job? = null
    private var loggedRawSampleCount = 0

    // Rolling IBI window (ms) for RMSSD HRV computation
    private val ibiWindowMs = ArrayDeque<Double>(32)
    private val hrEstRrWindowMs = ArrayDeque<Double>(24)
    private var hasRealIbi = false
    private var lastHrBeatTimestampNs = 0L
    private var lastPpgVal = 0.0
    private var prevPpgRising = false
    private var lastPpgPeakNs = 0L
    private var ppgBaseline = 0.0
    private var lastHrSum = 0L
    private var lastHrCount = 0L

    // Auto-workout detection & passive HRR tracking
    private var rollingAccelMag = 0.0
    private var activeEffortSeconds = 0
    private var lastAutoSuggestMs = 0L
    private var passivePeakHr = 0
    private var passiveHighHrSec = 0
    private var passiveRecoveryActive = false
    private var passiveRecoverySec = 0
    private var passiveHrr1: Int? = null
    private var lastTelemetrySyncMs = 0L

    fun dismissAutoWorkoutSuggestion() {
        _state.value = _state.value.copy(autoWorkoutSuggestion = null)
        lastAutoSuggestMs = System.currentTimeMillis()
    }

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val weekly = SomaBiometricsStore.load(ctx)
        _state.value = _state.value.copy(
            weekly = weekly,
            currentHrvMs = _state.value.currentHrvMs ?: weekly.lastHrvMs
        )
    }

    fun startPassiveMonitoring(context: Context) {
        init(context)
        registerSensors(context.applicationContext)
        startPassiveObserver(context.applicationContext)
    }

    fun stopPassiveMonitoringIfIdle() {
        if (_state.value.phase == WorkoutPhase.IDLE && !SomaNoiseMonitor.state.value.isMonitoring) {
            passiveJob?.cancel()
            passiveJob = null
            unregisterSensors()
        }
    }

    private fun startPassiveObserver(ctx: Context) {
        if (passiveJob?.isActive == true) return
        passiveJob = scope.launch {
            while (isActive) {
                delay(2000L)
                val s = _state.value
                val now = System.currentTimeMillis()

                // Periodic telemetry sync to OnePlus 15 SQLite DB (every 3 minutes when HR/HRV present)
                if (s.currentHr > 35 && now - lastTelemetrySyncMs >= 180_000L) {
                    lastTelemetrySyncMs = now
                    syncTelemetrySampleToPhone(ctx, s)
                }

                if (s.phase != WorkoutPhase.IDLE) {
                    activeEffortSeconds = 0
                    passiveRecoveryActive = false
                    continue
                }

                val hr = s.currentHr
                val moving = rollingAccelMag > 1.35 // catches push-ups, squats, kettlebells even with 0 steps
                if (s.isMovingActively != moving) {
                    _state.value = s.copy(isMovingActively = moving)
                }

                // 1) Auto-detect dynamic/strength workout: HR >= 58% maxHr AND active acceleration for >= 40s
                val effortThreshold = (s.weekly.maxHr * 0.58f).roundToInt()
                if (SomaBiometricsStore.isAutoDetectEnabled(ctx) && hr >= effortThreshold && moving) {
                    activeEffortSeconds += 2
                    if (activeEffortSeconds >= 40 && s.autoWorkoutSuggestion == null && now - lastAutoSuggestMs > 10 * 60 * 1000L) {
                        lastAutoSuggestMs = now
                        vibrate(ctx, 110)
                        _state.value = _state.value.copy(
                            autoWorkoutSuggestion = "Нагрузка ($hr BPM) — записать?"
                        )
                    }
                } else {
                    activeEffortSeconds = (activeEffortSeconds - 2).coerceAtLeast(0)
                }

                // 2) Passive background HRR-1 / HRR-2 after spontaneous exertion (HR >= 110 BPM for >= 24s)
                if (!passiveRecoveryActive) {
                    if (hr >= 110) {
                        passiveHighHrSec += 2
                        passivePeakHr = maxOf(passivePeakHr, hr)
                    } else if (passiveHighHrSec >= 24 && !moving && hr in 40 until passivePeakHr) {
                        // Exertion ended -> passively measure HRR-1 (60s) and HRR-2 (120s)!
                        passiveRecoveryActive = true
                        passiveRecoverySec = 0
                        passiveHrr1 = null
                        passiveHighHrSec = 0
                        Log.i(TAG, "Passive HRR tracking started from peak $passivePeakHr BPM")
                    } else if (hr < 102) {
                        passiveHighHrSec = (passiveHighHrSec - 2).coerceAtLeast(0)
                        if (passiveHighHrSec == 0) passivePeakHr = 0
                    }
                } else {
                    passiveRecoverySec += 2
                    if (passiveRecoverySec in 60..61 && hr > 35) {
                        passiveHrr1 = (passivePeakHr - hr).coerceAtLeast(0)
                        SomaBiometricsStore.saveLastHrr(ctx, passiveHrr1, null)
                        refreshWeeklyFromStore(ctx)
                    }
                    if (passiveRecoverySec >= 120 && hr > 35) {
                        val hrr2 = (passivePeakHr - hr).coerceAtLeast(0)
                        SomaBiometricsStore.saveLastHrr(ctx, passiveHrr1, hrr2)
                        refreshWeeklyFromStore(ctx)
                        syncPassiveHrrToPhone(ctx, passivePeakHr, passiveHrr1, hrr2)
                        passiveRecoveryActive = false
                        passivePeakHr = 0
                    }
                }
            }
        }
    }

    fun startWorkout(context: Context) {
        val ctx = context.applicationContext
        init(ctx)
        registerSensors(ctx)

        lastHrSum = 0L
        lastHrCount = 0L
        ibiWindowMs.clear()

        val initialHr = _state.value.currentHr
        _state.value = _state.value.copy(
            phase = WorkoutPhase.ACTIVE,
            elapsedSeconds = 0,
            peakHr = initialHr,
            avgHr = initialHr,
            sessionZone2Sec = 0,
            sessionZone3Sec = 0,
            sessionVigorousSec = 0,
            hrHistory = if (initialHr > 30) listOf(initialHr) else emptyList(),
            recoveryStartHr = 0,
            recoveryElapsedSec = 0,
            hrr1Drop = null,
            hrr2Drop = null
        )

        WorkoutForegroundService.start(ctx)
        startTicker(ctx)
    }

    fun beginHrrRecovery(context: Context) {
        val ctx = context.applicationContext
        val current = _state.value
        val startHr = if (current.currentHr > 40) current.currentHr else current.peakHr
        vibrate(ctx, 100)
        _state.value = current.copy(
            phase = WorkoutPhase.HRR_RECOVERY,
            recoveryStartHr = startHr,
            recoveryElapsedSec = 0,
            hrr1Drop = null,
            hrr2Drop = null
        )
    }

    fun finishWorkoutNow(context: Context) {
        val ctx = context.applicationContext
        val current = _state.value
        tickerJob?.cancel()
        tickerJob = null
        WorkoutForegroundService.stop(ctx)

        if (current.hrr1Drop != null || current.hrr2Drop != null) {
            SomaBiometricsStore.saveLastHrr(ctx, current.hrr1Drop, current.hrr2Drop)
        }
        val refreshedWeekly = SomaBiometricsStore.load(ctx)
        _state.value = current.copy(
            phase = WorkoutPhase.SUMMARY,
            weekly = refreshedWeekly
        )
        requestComplicationUpdate(ctx)
        syncSummaryToPhone(ctx, _state.value)
    }

    fun dismissSummary() {
        _state.value = _state.value.copy(phase = WorkoutPhase.IDLE)
    }

    fun refreshWeeklyFromStore(context: Context) {
        val ctx = context.applicationContext
        val weekly = SomaBiometricsStore.load(ctx)
        _state.value = _state.value.copy(weekly = weekly)
        requestComplicationUpdate(ctx)
    }

    fun computeZone(hr: Int, maxHr: Int): Int {
        if (hr < 40 || maxHr <= 0) return 0
        val pct = hr.toFloat() / maxHr.toFloat()
        return when {
            pct < 0.60f -> 1
            pct < 0.70f -> 2
            pct < 0.80f -> 3
            pct < 0.90f -> 4
            else -> 5
        }
    }

    private fun registerSensors(ctx: Context) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        var registeredAny = false

        val hrSensor = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (hrSensor != null) {
            val ok = sm.registerListener(this, hrSensor, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Registered TYPE_HEART_RATE (${hrSensor.name}): $ok")
            registeredAny = registeredAny || ok
        }

        // Scan ALL sensors so non-wakeup vendor sensors (Samsung HR Raw 69682, TYPE_HEART_BEAT 31) are found!
        val allSensors = sm.getSensorList(Sensor.TYPE_ALL)
        val heartBeatSensor = allSensors.firstOrNull { it.type == Sensor.TYPE_HEART_BEAT }
        if (heartBeatSensor != null) {
            val ok = sm.registerListener(this, heartBeatSensor, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Registered TYPE_HEART_BEAT (${heartBeatSensor.name}): $ok")
            registeredAny = registeredAny || ok
        }

        val rawSensor = allSensors.firstOrNull {
            it.type == SAMSUNG_HR_RAW_TYPE || it.stringType.contains("hr_raw", ignoreCase = true)
        }
        if (rawSensor != null) {
            val ok = sm.registerListener(this, rawSensor, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Registered Samsung HR Raw (${rawSensor.type} / ${rawSensor.name}): $ok")
            registeredAny = registeredAny || ok
        }

        // Linear acceleration for dynamic/strength workout auto-detection (push-ups, squats, etc.)
        val accel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (accel != null) {
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
        }

        _state.value = _state.value.copy(isSensorActive = registeredAny)
    }

    private fun unregisterSensors() {
        sensorManager?.unregisterListener(this)
        _state.value = _state.value.copy(isSensorActive = false)
    }

    private fun startTicker(ctx: Context) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                val s = _state.value
                when (s.phase) {
                    WorkoutPhase.ACTIVE -> {
                        val hr = s.currentHr
                        val zone = computeZone(hr, s.weekly.maxHr)
                        if (zone in 2..5) {
                            SomaBiometricsStore.addZoneSecond(ctx, zone)
                        }
                        if (hr > 35) {
                            lastHrSum += hr
                            lastHrCount++
                        }
                        val newElapsed = s.elapsedSeconds + 1
                        val newHistory = if (newElapsed % 2 == 0 && hr > 35) {
                            (s.hrHistory + hr).takeLast(90)
                        } else {
                            s.hrHistory
                        }
                        val updatedWeekly = if (newElapsed % 5 == 0) {
                            SomaBiometricsStore.load(ctx)
                        } else {
                            s.weekly.copy(
                                zone2Seconds = s.weekly.zone2Seconds + if (zone == 2) 1 else 0,
                                zone3Seconds = s.weekly.zone3Seconds + if (zone == 3) 1 else 0,
                                vigorousSeconds = s.weekly.vigorousSeconds + if (zone == 4 || zone == 5) 1 else 0
                            )
                        }

                        _state.value = s.copy(
                            elapsedSeconds = newElapsed,
                            currentZone = zone,
                            peakHr = maxOf(s.peakHr, hr),
                            avgHr = if (lastHrCount > 0) (lastHrSum / lastHrCount).toInt() else hr,
                            sessionZone2Sec = s.sessionZone2Sec + if (zone == 2) 1 else 0,
                            sessionZone3Sec = s.sessionZone3Sec + if (zone == 3) 1 else 0,
                            sessionVigorousSec = s.sessionVigorousSec + if (zone == 4 || zone == 5) 1 else 0,
                            hrHistory = newHistory,
                            weekly = updatedWeekly
                        )

                        if (newElapsed % 15 == 0) {
                            WorkoutForegroundService.updateNotification(ctx, hr, zone, newElapsed)
                        }
                    }

                    WorkoutPhase.HRR_RECOVERY -> {
                        val nextSec = s.recoveryElapsedSec + 1
                        val hr = s.currentHr
                        var hrr1 = s.hrr1Drop
                        var hrr2 = s.hrr2Drop

                        if (nextSec == 60 && hr > 35 && s.recoveryStartHr > 35) {
                            hrr1 = (s.recoveryStartHr - hr).coerceAtLeast(0)
                            vibrate(ctx, 120)
                            SomaBiometricsStore.saveLastHrr(ctx, hrr1, null)
                        }
                        if (nextSec >= 120 && hr > 35 && s.recoveryStartHr > 35) {
                            hrr2 = (s.recoveryStartHr - hr).coerceAtLeast(0)
                            vibrate(ctx, 180)
                            SomaBiometricsStore.saveLastHrr(ctx, hrr1, hrr2)
                            _state.value = s.copy(
                                recoveryElapsedSec = nextSec,
                                hrr1Drop = hrr1,
                                hrr2Drop = hrr2
                            )
                            finishWorkoutNow(ctx)
                            return@launch
                        }

                        val newHistory = if (nextSec % 2 == 0 && hr > 35) {
                            (s.hrHistory + hr).takeLast(90)
                        } else {
                            s.hrHistory
                        }
                        _state.value = s.copy(
                            recoveryElapsedSec = nextSec,
                            hrr1Drop = hrr1,
                            hrr2Drop = hrr2,
                            hrHistory = newHistory
                        )
                    }

                    else -> break
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        if (values.isEmpty()) return

        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val hr = values[0].roundToInt()
                if (hr in 35..230) {
                    val s = _state.value
                    val zone = computeZone(hr, s.weekly.maxHr)
                    _state.value = s.copy(
                        currentHr = hr,
                        currentZone = zone,
                        isSensorActive = true
                    )
                    // Fallback RR-interval HRV estimator from continuous 1 Hz HR stream when raw IBI isn't emitting yet
                    if (!hasRealIbi && values[0] > 35f) {
                        pushEstimatedRrFromHr(60_000.0 / values[0].toDouble())
                    }
                }
            }

            Sensor.TYPE_HEART_BEAT -> {
                if (values[0] > 0.5f) {
                    val nowNs = event.timestamp
                    if (lastHrBeatTimestampNs > 0L) {
                        val ibiMs = (nowNs - lastHrBeatTimestampNs) / 1_000_000.0
                        if (ibiMs in 300.0..1600.0) {
                            hasRealIbi = true
                            pushIbiSample(ibiMs)
                        }
                    }
                    lastHrBeatTimestampNs = nowNs
                }
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (values.size >= 3) {
                    val mag = sqrt(
                        (values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble()
                    )
                    rollingAccelMag = rollingAccelMag * 0.85 + mag * 0.15
                }
            }

            SAMSUNG_HR_RAW_TYPE -> {
                if (loggedRawSampleCount < 6) {
                    loggedRawSampleCount++
                    val sampleStr = values.joinToString(", ")
                    Log.i(TAG, "HR_RAW sample #$loggedRawSampleCount: $sampleStr")
                    if (loggedRawSampleCount == 1) {
                        appContext?.let { ctx ->
                            scope.launch {
                                runCatching {
                                    val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
                                    nodes.forEach { node ->
                                        Wearable.getMessageClient(ctx)
                                            .sendMessage(node.id, "/soma/debug_hr_raw", sampleStr.toByteArray(Charsets.UTF_8))
                                            .await()
                                    }
                                }
                            }
                        }
                    }
                }
                // 1) Check direct IBI in ms (300..1600) or seconds (0.3..1.6)
                val ibiMsDirect = values.drop(1).firstOrNull { it in 300f..1600f }?.toDouble()
                    ?: values.drop(1).firstOrNull { it in 0.30f..1.60f }?.toDouble()?.times(1000.0)
                if (ibiMsDirect != null) {
                    hasRealIbi = true
                    pushIbiSample(ibiMsDirect)
                } else if (values[0] > 5000f) {
                    // 2) Raw PPG optical waveform -> lightweight real-time peak-to-peak IBI detector
                    processRawPpgPeak(values[0].toDouble(), event.timestamp)
                }

                val rawHr = values[0].roundToInt()
                if (rawHr in 35..230 && _state.value.currentHr == 0) {
                    val s = _state.value
                    _state.value = s.copy(
                        currentHr = rawHr,
                        currentZone = computeZone(rawHr, s.weekly.maxHr),
                        isSensorActive = true
                    )
                }
            }
        }
    }

    private fun processRawPpgPeak(sample: Double, timestampNs: Long) {
        if (ppgBaseline == 0.0) {
            ppgBaseline = sample
            lastPpgVal = sample
            return
        }
        ppgBaseline = ppgBaseline * 0.96 + sample * 0.04
        val rising = sample > lastPpgVal
        if (prevPpgRising && !rising && lastPpgVal > ppgBaseline) {
            if (lastPpgPeakNs > 0L) {
                val ibiMs = (timestampNs - lastPpgPeakNs) / 1_000_000.0
                if (ibiMs in 350.0..1500.0) {
                    hasRealIbi = true
                    pushIbiSample(ibiMs)
                }
            }
            lastPpgPeakNs = timestampNs
        }
        prevPpgRising = rising
        lastPpgVal = sample
    }

    private fun pushEstimatedRrFromHr(rrMs: Double) {
        if (hrEstRrWindowMs.size >= 20) hrEstRrWindowMs.removeFirst()
        hrEstRrWindowMs.addLast(rrMs)
        if (hrEstRrWindowMs.size >= 5) {
            val list = hrEstRrWindowMs.toList()
            var sumSq = 0.0
            for (i in 1 until list.size) {
                val d = list[i] - list[i - 1]
                sumSq += d * d
            }
            val estRmssd = sqrt(sumSq / (list.size - 1)).roundToInt().coerceIn(8, 180)
            _state.value = _state.value.copy(
                currentHrvMs = estRmssd,
                hrvSource = "HR~"
            )
            appContext?.let { SomaBiometricsStore.saveLastHrv(it, estRmssd) }
        }
    }

    private fun pushIbiSample(ibiMs: Double) {
        if (ibiWindowMs.isNotEmpty()) {
            val prev = ibiWindowMs.last()
            // Reject motion artifacts (>35% jump between adjacent beats)
            if (kotlin.math.abs(ibiMs - prev) > prev * 0.35) return
        }
        if (ibiWindowMs.size >= 30) ibiWindowMs.removeFirst()
        ibiWindowMs.addLast(ibiMs)

        if (ibiWindowMs.size >= 6) {
            val list = ibiWindowMs.toList()
            var sumSqDiff = 0.0
            for (i in 1 until list.size) {
                val diff = list[i] - list[i - 1]
                sumSqDiff += diff * diff
            }
            val rmssd = sqrt(sumSqDiff / (list.size - 1)).roundToInt().coerceIn(5, 220)
            _state.value = _state.value.copy(
                currentHrvMs = rmssd,
                hrvSource = "IBI RMSSD"
            )
            appContext?.let { SomaBiometricsStore.saveLastHrv(it, rmssd) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun requestComplicationUpdate(ctx: Context) {
        runCatching {
            val requester = ComplicationDataSourceUpdateRequester.create(
                ctx,
                ComponentName(ctx, CardioZoneComplicationService::class.java)
            )
            requester.requestUpdateAll()
        }
    }

    private fun syncSummaryToPhone(ctx: Context, s: BiometricsUiState) {
        scope.launch {
            runCatching {
                val json = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("durationSec", s.elapsedSeconds)
                    put("avgHr", s.avgHr)
                    put("peakHr", s.peakHr)
                    put("hrvMs", s.currentHrvMs ?: -1)
                    put("hrr1", s.hrr1Drop ?: -1)
                    put("hrr2", s.hrr2Drop ?: -1)
                    put("sessionZ2Sec", s.sessionZone2Sec)
                    put("sessionZ3Sec", s.sessionZone3Sec)
                    put("sessionVigSec", s.sessionVigorousSec)
                    put("weeklyZ2Min", s.weekly.zone2Minutes)
                    put("weeklyZ2Goal", s.weekly.zone2GoalMin)
                    put("weeklyVigMin", s.weekly.vigorousMinutes)
                    put("weeklyVigGoal", s.weekly.vigorousGoalMin)
                }
                val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
                nodes.forEach { node ->
                    Wearable.getMessageClient(ctx)
                        .sendMessage(
                            node.id,
                            "/soma/workout_summary",
                            json.toString().toByteArray(Charsets.UTF_8)
                        ).await()
                }
                Log.i(TAG, "Synced workout summary to phone: $json")
            }.onFailure { Log.w(TAG, "Failed syncing workout summary", it) }
        }
    }

    private fun syncTelemetrySampleToPhone(ctx: Context, s: BiometricsUiState) {
        scope.launch {
            runCatching {
                val json = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("hr", s.currentHr)
                    put("hrvMs", s.currentHrvMs ?: -1)
                    put("zone", s.currentZone)
                    put("db", SomaNoiseMonitor.state.value.currentDb)
                }
                val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
                nodes.forEach { node ->
                    Wearable.getMessageClient(ctx)
                        .sendMessage(
                            node.id,
                            "/soma/telemetry_sample",
                            json.toString().toByteArray(Charsets.UTF_8)
                        ).await()
                }
            }
        }
    }

    private fun syncPassiveHrrToPhone(ctx: Context, peakHr: Int, hrr1: Int?, hrr2: Int?) {
        scope.launch {
            runCatching {
                val json = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("peakHr", peakHr)
                    put("hrr1", hrr1 ?: -1)
                    put("hrr2", hrr2 ?: -1)
                }
                val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
                nodes.forEach { node ->
                    Wearable.getMessageClient(ctx)
                        .sendMessage(
                            node.id,
                            "/soma/passive_hrr",
                            json.toString().toByteArray(Charsets.UTF_8)
                        ).await()
                }
            }
        }
    }

    private fun vibrate(ctx: Context, ms: Long) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
