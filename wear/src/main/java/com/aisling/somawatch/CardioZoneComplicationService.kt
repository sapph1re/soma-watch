package com.aisling.somawatch

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class CardioZoneComplicationService : SuspendingComplicationDataSourceService() {

    private val emeraldRamp = ColorRamp(
        colors = intArrayOf(0xFF059669.toInt(), 0xFF10B981.toInt(), 0xFF34D399.toInt()),
        interpolated = true
    )

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.GOAL_PROGRESS -> GoalProgressComplicationData.Builder(
                value = 95f,
                targetValue = 150f,
                contentDescription = PlainComplicationText.Builder("Soma Zone 2: 95 of 150 min").build()
            )
                .setText(PlainComplicationText.Builder("95").build())
                .setTitle(PlainComplicationText.Builder("Z2").build())
                .setColorRamp(emeraldRamp)
                .build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 95f,
                min = 0f,
                max = 150f,
                contentDescription = PlainComplicationText.Builder("Soma Zone 2: 95 of 150 min").build()
            )
                .setText(PlainComplicationText.Builder("95").build())
                .setTitle(PlainComplicationText.Builder("Z2").build())
                .setColorRamp(emeraldRamp)
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("Zone 2: 95 min").build(),
                contentDescription = PlainComplicationText.Builder("Soma Zone 2: 95 of 150 min").build()
            )
                .setTitle(PlainComplicationText.Builder("HIIT: 32 min").build())
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
            ComplicationType.GOAL_PROGRESS -> GoalProgressComplicationData.Builder(
                value = z2Min.toFloat(),
                targetValue = goal.toFloat(),
                contentDescription = PlainComplicationText.Builder("Soma Zone 2: $z2Min of $goal min").build()
            )
                .setText(PlainComplicationText.Builder("$z2Min").build())
                .setTitle(PlainComplicationText.Builder("Z2").build())
                .setColorRamp(emeraldRamp)
                .setTapAction(tapAction)
                .build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = z2Min.toFloat().coerceAtMost(goal.toFloat()),
                min = 0f,
                max = goal.toFloat(),
                contentDescription = PlainComplicationText.Builder("Soma Zone 2: $z2Min of $goal min").build()
            )
                .setText(PlainComplicationText.Builder("$z2Min").build())
                .setTitle(PlainComplicationText.Builder("Z2").build())
                .setColorRamp(emeraldRamp)
                .setTapAction(tapAction)
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("Zone 2: $z2Min min").build(),
                contentDescription = PlainComplicationText.Builder("Soma Zone 2: $z2Min of $goal min").build()
            )
                .setTitle(PlainComplicationText.Builder("HIIT: ${stats.vigorousMinutes} min").build())
                .setTapAction(tapAction)
                .build()

            else -> null
        }
    }
}
