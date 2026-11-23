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

class CardioZoneComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 95f,
                min = 0f,
                max = 150f,
                contentDescription = PlainComplicationText.Builder("Soma Zone 2").build()
            )
                .setText(PlainComplicationText.Builder("95m").build())
                .setTitle(PlainComplicationText.Builder("Z2").build())
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("95/150").build(),
                contentDescription = PlainComplicationText.Builder("Soma Zone 2").build()
            )
                .setTitle(PlainComplicationText.Builder("Z2").build())
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("Zone 2: 95 / 150 мин").build(),
                contentDescription = PlainComplicationText.Builder("Soma Zone 2").build()
            )
                .setTitle(PlainComplicationText.Builder("HIIT: 32 / 75 мин").build())
                .build()

            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val stats = SomaBiometricsStore.load(applicationContext)
        val z2Min = stats.zone2Minutes
        val goal = stats.zone2GoalMin.coerceAtLeast(1)

        val tapIntent = Intent(this, WearMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tapAction = PendingIntent.getActivity(
            this,
            3001,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = z2Min.toFloat().coerceAtMost(goal.toFloat()),
                min = 0f,
                max = goal.toFloat(),
                contentDescription = PlainComplicationText.Builder("Soma Zone 2: $z2Min из $goal мин").build()
            )
                .setText(PlainComplicationText.Builder("${z2Min}m").build())
                .setTitle(PlainComplicationText.Builder("Z2").build())
                .setTapAction(tapAction)
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("$z2Min/$goal").build(),
                contentDescription = PlainComplicationText.Builder("Soma Zone 2: $z2Min из $goal мин").build()
            )
                .setTitle(PlainComplicationText.Builder("Z2").build())
                .setTapAction(tapAction)
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("Zone 2: $z2Min / $goal мин").build(),
                contentDescription = PlainComplicationText.Builder("Soma Zone 2: $z2Min из $goal мин").build()
            )
                .setTitle(PlainComplicationText.Builder("HIIT: ${stats.vigorousMinutes} / ${stats.vigorousGoalMin} мин").build())
                .setTapAction(tapAction)
                .build()

            else -> null
        }
    }
}
