package com.aisling.somawatch

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

data class RecoveryBout(
    val timestampMs: Long,
    val peakHr: Int,
    val endHr: Int,
    val dropBpm: Int,
    val observedSec: Int,
    val tauSec: Int,            // Imai T30 / mono-exponential time constant (seconds; lower = faster vagal brake)
    val slopeBpmPerMin: Int,    // Initial deceleration velocity (BPM/min)
    val projectedHrr60: Int,    // Measured 60s drop (if >=60s) or conservatively extrapolated eHRR-60 from tau
    val confidence: String,     // "measured_60s" or "extrapolated_t30"
    val restType: String,       // "passive" or "active"
    val context: String         // "intra_workout" or "daily"
)

/**
 * Continuous Heart Rate Recovery Kinetics engine based on Imai et al. (1994) T30
 * and mono-exponential decay fitting:
 *   HR(t) = HR_rest + (HR_peak - HR_rest) * exp(-t / tau)
 *
 * Detects every valid deceleration bout (>=18s, >=8 BPM drop) during workouts
 * (between HIIT intervals / strength sets) AND in daily life, gated by accelerometer context.
 */
class ContinuousHrrTracker(private val restingHrEstimate: Double = 60.0) {

    private data class Point(val timeSec: Int, val hr: Double, val accel: Double)

    private val hrSmoothBuf = ArrayDeque<Int>(3)
    private var inDecline = false
    private var boutPoints = mutableListOf<Point>()
    private var boutStartMs = 0L
    private var riseStreak = 0
    private var plateauStreak = 0
    private var elapsedClockSec = 0

    fun reset() {
        hrSmoothBuf.clear()
        inDecline = false
        boutPoints.clear()
        riseStreak = 0
        plateauStreak = 0
    }

    /**
     * Feed one sample every ~2 seconds. Returns a completed [RecoveryBout] whenever
     * a scientifically valid deceleration bout finishes (or reaches 60s/120s).
     */
    fun onSample(
        hr: Int,
        accelMag: Double,
        isWorkoutActive: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): RecoveryBout? {
        if (hr !in 40..230) return null
        elapsedClockSec += 2

        if (hrSmoothBuf.size >= 3) hrSmoothBuf.removeFirst()
        hrSmoothBuf.addLast(hr)
        val smoothHr = hrSmoothBuf.average()

        if (!inDecline) {
            if (smoothHr >= 105.0) {
                // Seed candidate peak; keep tracking local maximum until HR begins falling
                if (boutPoints.isEmpty() || smoothHr >= boutPoints.last().hr) {
                    boutPoints.clear()
                    boutPoints.add(Point(0, smoothHr, accelMag))
                    boutStartMs = nowMs
                } else if (boutPoints.first().hr - smoothHr >= 3.0) {
                    // Confirmed transition into recovery decline!
                    inDecline = true
                    riseStreak = 0
                    plateauStreak = 0
                    val dt = ((nowMs - boutStartMs) / 1000L).toInt().coerceAtLeast(2)
                    boutPoints.add(Point(dt, smoothHr, accelMag))
                }
            } else {
                boutPoints.clear()
            }
            return null
        }

        // Currently inside a recovery deceleration bout
        val dt = ((nowMs - boutStartMs) / 1000L).toInt().coerceAtLeast(boutPoints.last().timeSec + 2)
        val prevHr = boutPoints.last().hr
        boutPoints.add(Point(dt, smoothHr, accelMag))

        if (smoothHr > prevHr + 1.5) {
            riseStreak++
            plateauStreak = 0
        } else if (kotlin.math.abs(smoothHr - prevHr) < 0.8) {
            plateauStreak++
            riseStreak = 0
        } else {
            riseStreak = 0
            plateauStreak = 0
        }

        // End bout if HR rises again (next set/interval started), plateaus for 12s, or hits 90s
        val boutEnded = riseStreak >= 2 || plateauStreak >= 6 || dt >= 90
        if (!boutEnded) return null

        // Trim trailing rise samples before fitting
        val cleanPoints = if (riseStreak >= 2 && boutPoints.size > 3) {
            boutPoints.dropLast(riseStreak)
        } else {
            boutPoints.toList()
        }

        inDecline = false
        boutPoints.clear()
        riseStreak = 0
        plateauStreak = 0

        return evaluateBout(cleanPoints, boutStartMs, isWorkoutActive)
    }

    private fun evaluateBout(
        points: List<Point>,
        startMs: Long,
        isWorkoutActive: Boolean
    ): RecoveryBout? {
        if (points.size < 6) return null
        val durationSec = points.last().timeSec - points.first().timeSec
        if (durationSec < 18) return null // Reject short PPG glitches (<18s)

        val peakHr = points.first().hr
        val minPoint = points.minByOrNull { it.hr } ?: return null
        val endHr = minPoint.hr
        val dropBpm = (peakHr - endHr).roundToInt()
        if (dropBpm < 8) return null // Require meaningful >=8 BPM recovery

        // Use points up to the minimum HR (and within first 45s for Imai T30 vagal constant)
        val fitWindow = points.filter { it.timeSec <= minOf(minPoint.timeSec, 45) }
        val baseRest = minOf(restingHrEstimate, endHr - 6.0).coerceIn(42.0, 75.0)

        // Linearized exponential fit: y = ln(HR(t) - HR_rest) = a - (1/tau) * t
        var sumT = 0.0
        var sumY = 0.0
        var sumTT = 0.0
        var sumTY = 0.0
        var n = 0
        for (p in fitWindow) {
            val delta = (p.hr - baseRest).coerceAtLeast(2.0)
            val y = ln(delta)
            val t = p.timeSec.toDouble()
            sumT += t
            sumY += y
            sumTT += t * t
            sumTY += t * y
            n++
        }
        if (n < 4) return null
        val denom = n * sumTT - sumT * sumT
        if (denom <= 1e-6) return null
        val slopeLn = (n * sumTY - sumT * sumY) / denom
        if (slopeLn >= -0.002) return null // Not actually decaying exponentially

        val tauSec = (-1.0 / slopeLn).roundToInt().coerceIn(15, 240)

        // Linear deceleration velocity over first ~30s (BPM/min)
        val p30 = fitWindow.last()
        val dt30 = p30.timeSec.coerceAtLeast(10)
        val slopeBpmMin = (((peakHr - p30.hr) / dt30.toDouble()) * 60.0).roundToInt().coerceIn(5, 120)

        // Conservative 60-second projection vs direct measurement
        val isMeasured60 = durationSec >= 60
        val projectedHrr60 = if (isMeasured60) {
            val pt60 = points.minByOrNull { kotlin.math.abs(it.timeSec - 60) } ?: minPoint
            (peakHr - pt60.hr).roundToInt().coerceAtLeast(dropBpm)
        } else {
            val amp = (peakHr - baseRest).coerceAtLeast(10.0)
            val proj = (amp * (1.0 - exp(-60.0 / tauSec.toDouble()))).roundToInt()
            // Cap extrapolation conservatively to at most 1.75x observed drop so short windows never exaggerate
            proj.coerceIn(dropBpm, (dropBpm * 1.75).roundToInt().coerceAtMost(85))
        }

        val avgAccel = points.map { it.accel }.average()
        val restType = if (avgAccel < 0.95) "passive" else "active"

        return RecoveryBout(
            timestampMs = startMs,
            peakHr = peakHr.roundToInt(),
            endHr = endHr.roundToInt(),
            dropBpm = dropBpm,
            observedSec = durationSec,
            tauSec = tauSec,
            slopeBpmPerMin = slopeBpmMin,
            projectedHrr60 = projectedHrr60,
            confidence = if (isMeasured60) "measured_60s" else "extrapolated_t30",
            restType = restType,
            context = if (isWorkoutActive) "intra_workout" else "daily"
        )
    }
}
