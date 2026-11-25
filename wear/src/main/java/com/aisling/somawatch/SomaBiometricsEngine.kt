package com.aisling.somawatch

import android.content.ComponentName
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlinx.coroutines.flow.update
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
    val sessionZone1Sec: Int = 0,
    val sessionZone2Sec: Int = 0,
    val sessionZone3Sec: Int = 0,
    val sessionVigorousSec: Int = 0,
    val hrHistory: List<Int> = emptyList(),
    // HRR recovery tracking
    val recoveryStartHr: Int = 0,
    val recoveryElapsedSec: Int = 0,
    val hrr1Drop: Int? = null,
    val hrr2Drop: Int? = null,
    // Continuous Recovery Kinetics (Imai T30 / intra-workout bouts)
    val latestBout: RecoveryBout? = null,
    val sessionRecoveryBouts: List<RecoveryBout> = emptyList(),
    // Auto-detected dynamic/strength workout prompt & state
    val autoWorkoutSuggestion: String? = null,
    val autoDetectEnabled: Boolean = true,
    val wasAutoStarted: Boolean = false,
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
        lastTauSec = null,
        lastProjectedHrr60 = null,
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
    @Volatile
    private var lastHrEventElapsedMs = 0L
    private var lastSensorReregisterMs = 0L

    // Auto-workout detection & passive HRR tracking
    private var rollingAccelMag = 0.0
    @Volatile
    private var lastSignificantMoveElapsedMs = 0L
    private var activeEffortSeconds = 0
    private var autoIdleSeconds = 0
    private var lastAutoSuggestMs = 0L
    private var passivePeakHr = 0
    private var passiveHighHrSec = 0
    private var passiveRecoveryActive = false
    private var passiveRecoverySec = 0
    private var passiveHrr1: Int? = null
    private var lastTelemetrySyncMs = 0L
    private val continuousHrrTracker = ContinuousHrrTracker()

    fun dismissAutoWorkoutSuggestion() {
        _state.update { it.copy(autoWorkoutSuggestion = null) }
        lastAutoSuggestMs = System.currentTimeMillis()
    }

    fun setAutoDetectEnabled(context: Context, enabled: Boolean) {
        val ctx = context.applicationContext
        SomaBiometricsStore.setAutoDetectEnabled(ctx, enabled)
        _state.update { it.copy(autoDetectEnabled = enabled) }
        if (!enabled) {
            activeEffortSeconds = 0
            _state.update { it.copy(autoWorkoutSuggestion = null) }
        }
    }

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val weekly = SomaBiometricsStore.load(ctx)
        val autoDetect = SomaBiometricsStore.isAutoDetectEnabled(ctx)
        _state.update {
            it.copy(
                weekly = weekly,
                autoDetectEnabled = autoDetect,
                currentHrvMs = it.currentHrvMs ?: weekly.lastHrvMs
            )
        }
    }

    fun startPassiveMonitoring(context: Context) {
        val ctx = context.applicationContext
        init(ctx)
        if (!_state.value.isSensorActive) {
            registerSensors(ctx)
        }
        startPassiveObserver(ctx)
    }

    fun stopPassiveMonitoringIfIdle() {
        val keepAlive = _state.value.phase != WorkoutPhase.IDLE ||
            SomaMonitorService.isRunning ||
            SomaNoiseMonitor.state.value.isMonitoring ||
            appContext?.let { SomaBiometricsStore.isAutoDetectEnabled(it) } == true
        if (!keepAlive) {
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
                val nowElapsed = SystemClock.elapsedRealtime()

                // Watchdog: if HR sensor stalled in background while active, re-register
                if (s.phase == WorkoutPhase.IDLE &&
                    s.isSensorActive &&
                    lastHrEventElapsedMs > 0L &&
                    nowElapsed - lastHrEventElapsedMs > 30_000L &&
                    nowElapsed - lastSensorReregisterMs >= 30_000L
                ) {
                    lastSensorReregisterMs = nowElapsed
                    Log.w(TAG, "No passive HR event for >30s; re-registering sensors")
                    registerSensors(ctx)
                }

                // Periodic telemetry sync to OnePlus 15 SQLite DB (every 3 minutes when HR/HRV present)
                if (s.currentHr > 35 && now - lastTelemetrySyncMs >= 180_000L) {
                    lastTelemetrySyncMs = now
                    syncTelemetrySampleToPhone(ctx, s)
                }

                // Continuous Recovery Kinetics (Imai T30 / mono-exponential tau) — runs BOTH during workouts & daily life!
                if (s.currentHr > 40 && s.phase != WorkoutPhase.HRR_RECOVERY) {
                    val bout = continuousHrrTracker.onSample(
                        hr = s.currentHr,
                        accelMag = rollingAccelMag,
                        isWorkoutActive = (s.phase == WorkoutPhase.ACTIVE),
                        nowMs = now
                    )
                    if (bout != null) {
                        SomaBiometricsStore.saveRecoveryBout(ctx, bout)
                        val refreshed = SomaBiometricsStore.load(ctx)
                        val cur = _state.value
                        val updatedBouts = if (cur.phase == WorkoutPhase.ACTIVE) {
                            (cur.sessionRecoveryBouts + bout).takeLast(20)
                        } else {
                            cur.sessionRecoveryBouts
                        }
                        _state.value = cur.copy(
                            latestBout = bout,
                            sessionRecoveryBouts = updatedBouts,
                            weekly = refreshed
                        )
                        syncRecoveryBoutToPhone(ctx, bout)
                        Log.i(TAG, "RecoveryBout detected: peak=${bout.peakHr} drop=-${bout.dropBpm} in ${bout.observedSec}s -> tau=${bout.tauSec}s eHRR60=-${bout.projectedHrr60} (${bout.confidence}/${bout.restType})")
                    }
                }

                if (s.phase != WorkoutPhase.IDLE) {
                    activeEffortSeconds = 0
                    passiveRecoveryActive = false
                    continue
                }

                val hr = s.currentHr
                val movingRecently = (lastSignificantMoveElapsedMs > 0L && nowElapsed - lastSignificantMoveElapsedMs <= 18_000L) ||
                    rollingAccelMag > 0.55
                if (s.isMovingActively != movingRecently) {
                    _state.update { it.copy(isMovingActively = movingRecently) }
                }

                // 1) Auto-Cardio detection: sustained Zone 2+ effort (>=58% maxHr with movement, or >=64% with recent motion)
                val effortThreshold = (s.weekly.maxHr * 0.58f).roundToInt()
                val clearZone2Hr = (s.weekly.maxHr * 0.63f).roundToInt()
                val recentLightMotion = lastSignificantMoveElapsedMs > 0L && nowElapsed - lastSignificantMoveElapsedMs <= 45_000L
                val isCardioEffort = (hr >= effortThreshold && movingRecently) || (hr >= clearZone2Hr && recentLightMotion)

                if (SomaBiometricsStore.isAutoDetectEnabled(ctx) && isCardioEffort) {
                    activeEffortSeconds += 2
                    if (activeEffortSeconds >= 36 && now - lastAutoSuggestMs > 10 * 60 * 1000L) {
                        activeEffortSeconds = 0
                        lastAutoSuggestMs = now
                        Log.i(TAG, "Auto-Cardio triggered at $hr BPM (Zone ${computeZone(hr, s.weekly.maxHr)})")
                        vibrateDouble(ctx)
                        startWorkout(ctx, autoStarted = true)
                    }
                } else {
                    activeEffortSeconds = (activeEffortSeconds - 1).coerceAtLeast(0)
                }

                // 2) Passive background HRR-1 / HRR-2 after spontaneous exertion (HR >= 110 BPM for >= 24s)
                if (!passiveRecoveryActive) {
                    if (hr >= 110) {
                        passiveHighHrSec += 2
                        passivePeakHr = maxOf(passivePeakHr, hr)
                    } else if (passiveHighHrSec >= 24 && !movingRecently && hr in 40 until passivePeakHr) {
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

    fun startWorkout(context: Context, autoStarted: Boolean = false) {
        val ctx = context.applicationContext
        init(ctx)
        registerSensors(ctx)

        lastHrSum = 0L
        lastHrCount = 0L
        autoIdleSeconds = 0
        ibiWindowMs.clear()
        continuousHrrTracker.reset()

        _state.update { cur ->
            val initialHr = cur.currentHr
            cur.copy(
                phase = WorkoutPhase.ACTIVE,
                wasAutoStarted = autoStarted,
                autoWorkoutSuggestion = null,
                elapsedSeconds = 0,
                peakHr = initialHr,
                avgHr = initialHr,
                sessionZone1Sec = 0,
                sessionZone2Sec = 0,
                sessionZone3Sec = 0,
                sessionVigorousSec = 0,
                hrHistory = if (initialHr > 30) listOf(initialHr) else emptyList(),
                recoveryStartHr = 0,
                recoveryElapsedSec = 0,
                hrr1Drop = null,
                hrr2Drop = null,
                sessionRecoveryBouts = emptyList()
            )
        }

        WorkoutForegroundService.start(ctx, autoStarted = autoStarted)
        SomaMonitorService.stop(ctx)
        requestComplicationUpdate(ctx)
        startTicker(ctx)
    }

    fun beginHrrRecovery(context: Context) {
        val ctx = context.applicationContext
        vibrate(ctx, 100)
        _state.update { current ->
            val startHr = if (current.currentHr > 40) current.currentHr else current.peakHr
            current.copy(
                phase = WorkoutPhase.HRR_RECOVERY,
                recoveryStartHr = startHr,
                recoveryElapsedSec = 0,
                hrr1Drop = null,
                hrr2Drop = null
            )
        }
    }

    fun finishWorkoutNow(context: Context) {
        val ctx = context.applicationContext
        tickerJob?.cancel()
        tickerJob = null
        lastAutoSuggestMs = System.currentTimeMillis()
        autoIdleSeconds = 0

        // Hand off back to lightweight SomaMonitorService while WorkoutForegroundService is still foreground
        SomaMonitorService.start(ctx)
        WorkoutForegroundService.stop(ctx)

        val snapshotBeforeSave = _state.value
        if (snapshotBeforeSave.hrr1Drop != null || snapshotBeforeSave.hrr2Drop != null) {
            SomaBiometricsStore.saveLastHrr(ctx, snapshotBeforeSave.hrr1Drop, snapshotBeforeSave.hrr2Drop)
        }
        val refreshedWeekly = SomaBiometricsStore.load(ctx)
        _state.update {
            it.copy(
                phase = WorkoutPhase.SUMMARY,
                weekly = refreshedWeekly
            )
        }
        requestComplicationUpdate(ctx)
        syncSummaryToPhone(ctx, _state.value)
    }

    fun dismissSummary() {
        _state.update { it.copy(phase = WorkoutPhase.IDLE, wasAutoStarted = false) }
    }

    fun refreshWeeklyFromStore(context: Context) {
        val ctx = context.applicationContext
        val weekly = SomaBiometricsStore.load(ctx)
        val autoDetect = SomaBiometricsStore.isAutoDetectEnabled(ctx)
        _state.update { it.copy(weekly = weekly, autoDetectEnabled = autoDetect) }
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
        sm.unregisterListener(this)
        val mainHandler = Handler(Looper.getMainLooper())
        var registeredAny = false

        val hrSensor = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (hrSensor != null) {
            val ok = sm.registerListener(this, hrSensor, SensorManager.SENSOR_DELAY_FASTEST, mainHandler)
            Log.i(TAG, "Registered TYPE_HEART_RATE (${hrSensor.name}): $ok")
            registeredAny = registeredAny || ok
        }

        // Scan ALL sensors so non-wakeup vendor sensors (Samsung HR Raw 69682, TYPE_HEART_BEAT 31) are found!
        val allSensors = sm.getSensorList(Sensor.TYPE_ALL)
        val heartBeatSensor = allSensors.firstOrNull { it.type == Sensor.TYPE_HEART_BEAT }
        if (heartBeatSensor != null) {
            val ok = sm.registerListener(this, heartBeatSensor, SensorManager.SENSOR_DELAY_FASTEST, mainHandler)
            Log.i(TAG, "Registered TYPE_HEART_BEAT (${heartBeatSensor.name}): $ok")
            registeredAny = registeredAny || ok
        }

        val rawSensor = allSensors.firstOrNull {
            it.type == SAMSUNG_HR_RAW_TYPE || it.stringType.contains("hr_raw", ignoreCase = true)
        }
        if (rawSensor != null) {
            val ok = sm.registerListener(this, rawSensor, SensorManager.SENSOR_DELAY_FASTEST, mainHandler)
            Log.i(TAG, "Registered Samsung HR Raw (${rawSensor.type} / ${rawSensor.name}): $ok")
            registeredAny = registeredAny || ok
        }

        // Linear acceleration for dynamic/strength workout auto-detection (push-ups, squats, etc.)
        val accel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (accel != null) {
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL, mainHandler)
        }

        _state.update { it.copy(isSensorActive = registeredAny) }
    }

    private fun unregisterSensors() {
        sensorManager?.unregisterListener(this)
        _state.update { it.copy(isSensorActive = false) }
    }

    private fun startTicker(ctx: Context) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var lastTickElapsedMs = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1000L)
                val nowElapsedMs = SystemClock.elapsedRealtime()
                val deltaSec = ((nowElapsedMs - lastTickElapsedMs) / 1000L).toInt()
                if (deltaSec <= 0) continue
                lastTickElapsedMs += deltaSec * 1000L

                val snap = _state.value
                when (snap.phase) {
                    WorkoutPhase.ACTIVE -> {
                        val hr = snap.currentHr
                        val hrFresh = hr in 35..230 &&
                            (lastHrEventElapsedMs == 0L || nowElapsedMs - lastHrEventElapsedMs <= 30_000L)

                        if (lastHrEventElapsedMs > 0L &&
                            nowElapsedMs - lastHrEventElapsedMs > 15_000L &&
                            nowElapsedMs - lastSensorReregisterMs >= 15_000L
                        ) {
                            lastSensorReregisterMs = nowElapsedMs
                            Log.w(TAG, "No HR sensor event for >15s during workout; re-registering sensors")
                            registerSensors(ctx)
                        }

                        val zone = if (hrFresh) computeZone(hr, snap.weekly.maxHr) else 0
                        if (zone in 2..5) {
                            SomaBiometricsStore.addZoneSeconds(ctx, zone, deltaSec.toLong())
                        }
                        if (hrFresh) {
                            lastHrSum += hr.toLong() * deltaSec
                            lastHrCount += deltaSec.toLong()
                        }

                        val prevElapsed = snap.elapsedSeconds
                        val newElapsed = prevElapsed + deltaSec
                        val reloadStore = (newElapsed / 5) != (prevElapsed / 5)
                        val freshWeekly = if (reloadStore) SomaBiometricsStore.load(ctx) else null

                        _state.update { cur ->
                            val newHistory = if ((newElapsed / 2) != (prevElapsed / 2) && cur.currentHr > 35) {
                                (cur.hrHistory + cur.currentHr).takeLast(90)
                            } else {
                                cur.hrHistory
                            }
                            val updatedWeekly = freshWeekly ?: cur.weekly.copy(
                                zone2Seconds = cur.weekly.zone2Seconds + if (zone == 2) deltaSec.toLong() else 0L,
                                zone3Seconds = cur.weekly.zone3Seconds + if (zone == 3) deltaSec.toLong() else 0L,
                                vigorousSeconds = cur.weekly.vigorousSeconds + if (zone == 4 || zone == 5) deltaSec.toLong() else 0L
                            )
                            cur.copy(
                                elapsedSeconds = newElapsed,
                                currentZone = if (hrFresh) zone else cur.currentZone,
                                peakHr = if (hrFresh) maxOf(cur.peakHr, hr) else cur.peakHr,
                                avgHr = if (lastHrCount > 0) (lastHrSum / lastHrCount).toInt() else cur.avgHr,
                                sessionZone1Sec = cur.sessionZone1Sec + if (zone == 1) deltaSec else 0,
                                sessionZone2Sec = cur.sessionZone2Sec + if (zone == 2) deltaSec else 0,
                                sessionZone3Sec = cur.sessionZone3Sec + if (zone == 3) deltaSec else 0,
                                sessionVigorousSec = cur.sessionVigorousSec + if (zone == 4 || zone == 5) deltaSec else 0,
                                hrHistory = newHistory,
                                weekly = updatedWeekly
                            )
                        }

                        if ((newElapsed / 15) != (prevElapsed / 15)) {
                            WorkoutForegroundService.updateNotification(ctx, hr, zone, newElapsed, snap.wasAutoStarted)
                        }
                        if ((newElapsed / 30) != (prevElapsed / 30)) {
                            requestComplicationUpdate(ctx)
                        }
                        if (snap.wasAutoStarted) {
                            val movingRecently = (lastSignificantMoveElapsedMs > 0L && nowElapsedMs - lastSignificantMoveElapsedMs <= 30_000L) ||
                                rollingAccelMag > 0.45
                            val lowHrThreshold = (snap.weekly.maxHr * 0.55f).roundToInt()
                            if (hr in 35 until lowHrThreshold && !movingRecently) {
                                autoIdleSeconds += deltaSec
                                if (autoIdleSeconds >= 300) {
                                    Log.i(TAG, "Auto-Cardio resting for 5m; auto-finishing workout")
                                    finishWorkoutNow(ctx)
                                    return@launch
                                }
                            } else if (hr >= lowHrThreshold || movingRecently) {
                                autoIdleSeconds = 0
                            }
                        }
                    }

                    WorkoutPhase.HRR_RECOVERY -> {
                        val prevSec = snap.recoveryElapsedSec
                        val nextSec = prevSec + deltaSec
                        val hr = snap.currentHr
                        var hrr1 = snap.hrr1Drop
                        var hrr2 = snap.hrr2Drop

                        if (hrr1 == null && nextSec >= 60 && hr > 35 && snap.recoveryStartHr > 35) {
                            hrr1 = (snap.recoveryStartHr - hr).coerceAtLeast(0)
                            vibrate(ctx, 120)
                            SomaBiometricsStore.saveLastHrr(ctx, hrr1, null)
                        }
                        if (nextSec >= 120 && hr > 35 && snap.recoveryStartHr > 35) {
                            hrr2 = (snap.recoveryStartHr - hr).coerceAtLeast(0)
                            vibrate(ctx, 180)
                            SomaBiometricsStore.saveLastHrr(ctx, hrr1, hrr2)
                            _state.update { cur ->
                                cur.copy(
                                    recoveryElapsedSec = nextSec,
                                    hrr1Drop = hrr1,
                                    hrr2Drop = hrr2
                                )
                            }
                            finishWorkoutNow(ctx)
                            return@launch
                        }

                        _state.update { cur ->
                            val newHistory = if ((nextSec / 2) != (prevSec / 2) && cur.currentHr > 35) {
                                (cur.hrHistory + cur.currentHr).takeLast(90)
                            } else {
                                cur.hrHistory
                            }
                            cur.copy(
                                recoveryElapsedSec = nextSec,
                                hrr1Drop = hrr1,
                                hrr2Drop = hrr2,
                                hrHistory = newHistory
                            )
                        }
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
                    lastHrEventElapsedMs = SystemClock.elapsedRealtime()
                    _state.update { s ->
                        s.copy(
                            currentHr = hr,
                            currentZone = computeZone(hr, s.weekly.maxHr),
                            isSensorActive = true
                        )
                    }
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
                    if (mag > 0.65 || rollingAccelMag > 0.45) {
                        lastSignificantMoveElapsedMs = SystemClock.elapsedRealtime()
                    }
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
                    lastHrEventElapsedMs = SystemClock.elapsedRealtime()
                    _state.update { s ->
                        if (s.currentHr == 0) {
                            s.copy(
                                currentHr = rawHr,
                                currentZone = computeZone(rawHr, s.weekly.maxHr),
                                isSensorActive = true
                            )
                        } else {
                            s
                        }
                    }
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
            _state.update {
                it.copy(
                    currentHrvMs = estRmssd,
                    hrvSource = "HR~"
                )
            }
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
            _state.update {
                it.copy(
                    currentHrvMs = rmssd,
                    hrvSource = "IBI RMSSD"
                )
            }
            appContext?.let { SomaBiometricsStore.saveLastHrv(it, rmssd) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun requestComplicationUpdate(ctx: Context) {
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                ctx,
                ComponentName(ctx, CardioZoneComplicationService::class.java)
            ).requestUpdateAll()
            ComplicationDataSourceUpdateRequester.create(
                ctx,
                ComponentName(ctx, CardioRingComplicationService::class.java)
            ).requestUpdateAll()
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
                    put("sessionZ1Sec", s.sessionZone1Sec)
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

    private fun syncRecoveryBoutToPhone(ctx: Context, bout: RecoveryBout) {
        scope.launch {
            runCatching {
                val json = JSONObject().apply {
                    put("timestamp", bout.timestampMs)
                    put("peakHr", bout.peakHr)
                    put("endHr", bout.endHr)
                    put("dropBpm", bout.dropBpm)
                    put("observedSec", bout.observedSec)
                    put("tauSec", bout.tauSec)
                    put("slopeBpmPerMin", bout.slopeBpmPerMin)
                    put("projectedHrr60", bout.projectedHrr60)
                    put("confidence", bout.confidence)
                    put("restType", bout.restType)
                    put("context", bout.context)
                }
                val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
                nodes.forEach { node ->
                    Wearable.getMessageClient(ctx)
                        .sendMessage(
                            node.id,
                            "/soma/recovery_bout",
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

    private fun vibrateDouble(ctx: Context) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 120), -1))
        }
    }
}
