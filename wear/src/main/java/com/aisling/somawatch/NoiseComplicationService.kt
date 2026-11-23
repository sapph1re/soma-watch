package com.aisling.somawatch

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class NoiseComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 54f,
                min = 30f,
                max = 115f,
                contentDescription = PlainComplicationText.Builder("Soma Шум дБ").build()
            )
                .setText(PlainComplicationText.Builder("54дБ").build())
                .setTitle(PlainComplicationText.Builder("Шум").build())
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("54дБ").build(),
                contentDescription = PlainComplicationText.Builder("Soma Шум дБ").build()
            )
                .setTitle(PlainComplicationText.Builder("Шум").build())
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("54 дБ (порог 85)").build(),
                contentDescription = PlainComplicationText.Builder("Soma Шум дБ").build()
            )
                .setTitle(PlainComplicationText.Builder("Шум • Пик 68 дБ").build())
                .build()

            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val ctx = applicationContext
        val live = SomaNoiseMonitor.state.value
        val db = live.currentDb.takeIf { it > 0 } ?: SomaBiometricsStore.getLastNoiseDb(ctx)
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

        val shortValue = if (db > 0) "${db}дБ" else "--"
        val description = if (db > 0) {
            "Шум: $db дБ, пик сегодня: $peak дБ, порог: $threshold дБ"
        } else {
            "Шум: нет замера"
        }

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = if (db > 0) db.toFloat().coerceIn(30f, 115f) else 30f,
                min = 30f,
                max = 115f,
                contentDescription = PlainComplicationText.Builder(description).build()
            )
                .setText(PlainComplicationText.Builder(shortValue).build())
                .setTitle(PlainComplicationText.Builder("Шум").build())
                .setTapAction(tapAction)
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(shortValue).build(),
                contentDescription = PlainComplicationText.Builder(description).build()
            )
                .setTitle(PlainComplicationText.Builder("Шум").build())
                .setTapAction(tapAction)
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(
                    if (db > 0) "$db дБ (порог $threshold)" else "Шум: нет замера"
                ).build(),
                contentDescription = PlainComplicationText.Builder(description).build()
            )
                .setTitle(
                    PlainComplicationText.Builder(
                        if (peak > 0) "Шум • Пик $peak дБ" else "Soma Шум"
                    ).build()
                )
                .setTapAction(tapAction)
                .build()

            else -> null
        }
    }
}
