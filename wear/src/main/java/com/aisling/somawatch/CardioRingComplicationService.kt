package com.aisling.somawatch

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Dedicated SMALL_IMAGE complication that renders a custom full-size circular progress ring
 * with weekly Blueprint Cardio Load (Z2×1 + Z3×1.5 + Z4-5×2) in the center, working even on
 * watch face slots that do not accept third-party RANGED_VALUE / GOAL_PROGRESS complications.
 */
class CardioRingComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) return null
        return buildRingComplication(loadMin = 145, goalMin = 300, tapAction = null)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) return null

        val stats = SomaBiometricsStore.load(applicationContext)
        val loadMin = stats.cardioLoadMinutes
        val goal = stats.cardioLoadGoalMin.coerceAtLeast(1)

        val tapIntent = Intent(this, WearMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tapAction = PendingIntent.getActivity(
            this,
            3003,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return buildRingComplication(loadMin = loadMin, goalMin = goal, tapAction = tapAction)
    }

    private fun buildRingComplication(
        loadMin: Int,
        goalMin: Int,
        tapAction: PendingIntent?
    ): SmallImageComplicationData {
        val activeBitmap = Zone2RingRenderer.renderFullRingBitmap(
            z2Min = loadMin,
            goalMin = goalMin,
            sizePx = 192,
            ambient = false,
            centerLabel = "LOAD"
        )
        val ambientBitmap = Zone2RingRenderer.renderFullRingBitmap(
            z2Min = loadMin,
            goalMin = goalMin,
            sizePx = 192,
            ambient = true,
            centerLabel = "LOAD"
        )

        val smallImage = SmallImage.Builder(
            image = Icon.createWithBitmap(activeBitmap),
            type = SmallImageType.PHOTO
        )
            .setAmbientImage(Icon.createWithBitmap(ambientBitmap))
            .build()

        return SmallImageComplicationData.Builder(
            smallImage = smallImage,
            contentDescription = PlainComplicationText.Builder("Soma Cardio Load: $loadMin of $goalMin eq.min").build()
        )
            .setTapAction(tapAction)
            .build()
    }
}
