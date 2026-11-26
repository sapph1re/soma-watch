package com.aisling.somawatch

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class CardioZoneComplicationService : SuspendingComplicationDataSourceService() {

    private val emeraldRamp = ColorRamp(
        colors = intArrayOf(0xFF059669.toInt(), 0xFF10B981.toInt(), 0xFF34D399.toInt()),
        interpolated = true
    )

    private fun buildMiniRingImage(z2Min: Int, goalMin: Int): SmallImage {
        val activeBmp = Zone2RingRenderer.renderMiniRingBitmap(z2Min, goalMin, sizePx = 96, ambient = false)
        val ambientBmp = Zone2RingRenderer.renderMiniRingBitmap(z2Min, goalMin, sizePx = 96, ambient = true)
        return SmallImage.Builder(
            image = Icon.createWithBitmap(activeBmp),
            type = SmallImageType.PHOTO
        )
            .setAmbientImage(Icon.createWithBitmap(ambientBmp))
            .build()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.GOAL_PROGRESS -> GoalProgressComplicationData.Builder(
                value = 145f,
                targetValue = 300f,
                contentDescription = PlainComplicationText.Builder("Soma Cardio Load: 145 of 300 eq.min").build()
            )
                .setText(PlainComplicationText.Builder("145").build())
                .setTitle(PlainComplicationText.Builder("LOAD").build())
                .setColorRamp(emeraldRamp)
                .build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 145f,
                min = 0f,
                max = 300f,
                contentDescription = PlainComplicationText.Builder("Soma Cardio Load: 145 of 300 eq.min").build()
            )
                .setText(PlainComplicationText.Builder("145").build())
                .setTitle(PlainComplicationText.Builder("LOAD").build())
                .setColorRamp(emeraldRamp)
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("145").build(),
                contentDescription = PlainComplicationText.Builder("Soma Cardio Load: 145 of 300 eq.min").build()
            )
                .setTitle(PlainComplicationText.Builder("LOAD").build())
                .setSmallImage(buildMiniRingImage(145, 300))
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("Cardio: 145/300 eq").build(),
                contentDescription = PlainComplicationText.Builder("Soma Cardio Load: 145 of 300 eq.min").build()
            )
                .setTitle(PlainComplicationText.Builder("Z2-3: 85m • HIIT: 30m").build())
                .build()

            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val stats = SomaBiometricsStore.load(applicationContext)
        val loadMin = stats.cardioLoadMinutes
        val goal = stats.cardioLoadGoalMin.coerceAtLeast(1)
        val aeroMin = stats.aerobicMinutes
        val vigMin = stats.vigorousMinutes
        val desc = "Soma Cardio Load: $loadMin of $goal eq.min (Z2-3: ${aeroMin}m, HIIT: ${vigMin}m)"

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
                value = loadMin.toFloat(),
                targetValue = goal.toFloat(),
                contentDescription = PlainComplicationText.Builder(desc).build()
            )
                .setText(PlainComplicationText.Builder("$loadMin").build())
                .setTitle(PlainComplicationText.Builder("LOAD").build())
                .setColorRamp(emeraldRamp)
                .setTapAction(tapAction)
                .build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = loadMin.toFloat().coerceAtMost(goal.toFloat()),
                min = 0f,
                max = goal.toFloat(),
                contentDescription = PlainComplicationText.Builder(desc).build()
            )
                .setText(PlainComplicationText.Builder("$loadMin").build())
                .setTitle(PlainComplicationText.Builder("LOAD").build())
                .setColorRamp(emeraldRamp)
                .setTapAction(tapAction)
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("$loadMin").build(),
                contentDescription = PlainComplicationText.Builder(desc).build()
            )
                .setTitle(PlainComplicationText.Builder("LOAD").build())
                .setSmallImage(buildMiniRingImage(loadMin, goal))
                .setTapAction(tapAction)
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("Cardio: $loadMin/$goal eq").build(),
                contentDescription = PlainComplicationText.Builder(desc).build()
            )
                .setTitle(PlainComplicationText.Builder("Z2-3: ${aeroMin}m • HIIT: ${vigMin}m").build())
                .setTapAction(tapAction)
                .build()

            else -> null
        }
    }
}
