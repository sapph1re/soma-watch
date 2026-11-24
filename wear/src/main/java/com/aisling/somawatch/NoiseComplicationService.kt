package com.aisling.somawatch

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.withTimeoutOrNull

class NoiseComplicationService : SuspendingComplicationDataSourceService() {

    private val noiseRamp = ColorRamp(
        colors = intArrayOf(0xFF10B981.toInt(), 0xFFF59E0B.toInt(), 0xFFF43F5E.toInt()),
        interpolated = true
    )

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 54f,
                min = 30f,
                max = 115f,
                contentDescription = PlainComplicationText.Builder("54 dB").build()
            )
                .setText(PlainComplicationText.Builder("54").build())
                .setTitle(PlainComplicationText.Builder("dB").build())
                .setColorRamp(noiseRamp)
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("54").build(),
                contentDescription = PlainComplicationText.Builder("54 dB").build()
            )
                .setTitle(PlainComplicationText.Builder("dB").build())
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("54 dB").build(),
                contentDescription = PlainComplicationText.Builder("54 dB, peak 68 dB").build()
            )
                .setTitle(PlainComplicationText.Builder("Peak 68 dB • Limit 85").build())
                .build()

            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val ctx = applicationContext
        val lastTs = SomaBiometricsStore.getLastNoiseTimestamp(ctx)
        val ageMs = System.currentTimeMillis() - lastTs

        // Attempt a fresh quick sample when stale (>25s) so watch face complications never stay stuck
        val freshDb = if (ageMs > 25_000L && !SomaNoiseMonitor.isVoiceRecordingActive) {
            withTimeoutOrNull(1200L) {
                SomaNoiseMonitor.sampleOnceForComplication(ctx)
            }
        } else {
            null
        }

        val live = SomaNoiseMonitor.state.value
        val db = freshDb ?: live.currentDb.takeIf { it > 0 } ?: SomaBiometricsStore.getLastNoiseDb(ctx)
        val peak = maxOf(live.peakDbToday, SomaBiometricsStore.getPeakNoiseDbToday(ctx))
        val threshold = SomaBiometricsStore.getNoiseThresholdDb(ctx)

        val tapIntent = Intent(this, WearMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tapAction = PendingIntent.getActivity(
            this,
            3002,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val numberText = if (db > 0) "$db" else "--"
        val description = if (db > 0) {
            "$db dB, today peak $peak dB, limit $threshold dB"
        } else {
            "Sound level: no reading"
        }

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = if (db > 0) db.toFloat().coerceIn(30f, 115f) else 30f,
                min = 30f,
                max = 115f,
                contentDescription = PlainComplicationText.Builder(description).build()
            )
                .setText(PlainComplicationText.Builder(numberText).build())
                .setTitle(PlainComplicationText.Builder("dB").build())
                .setColorRamp(noiseRamp)
                .setTapAction(tapAction)
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(numberText).build(),
                contentDescription = PlainComplicationText.Builder(description).build()
            )
                .setTitle(PlainComplicationText.Builder("dB").build())
                .setTapAction(tapAction)
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(
                    if (db > 0) "$db dB" else "-- dB"
                ).build(),
                contentDescription = PlainComplicationText.Builder(description).build()
            )
                .setTitle(
                    PlainComplicationText.Builder(
                        if (peak > 0) "Peak $peak dB • Limit $threshold" else "Limit $threshold dB"
                    ).build()
                )
                .setTapAction(tapAction)
                .build()

            else -> null
        }
    }
}
