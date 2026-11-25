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
 * with weekly Zone 2 minutes in the center, working even on watch face slots that do not
 * accept third-party RANGED_VALUE / GOAL_PROGRESS complications.
 */
class CardioRingComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) return null
        return buildRingComplication(z2Min = 95, goalMin = 150, tapAction = null)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) return null

        val stats = SomaBiometricsStore.load(applicationContext)
        val z2Min = stats.zone2Minutes
        val goal = stats.zone2GoalMin.coerceAtLeast(1)

        val tapIntent = Intent(this, WearMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tapAction = PendingIntent.getActivity(
            this,
            3003,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return buildRingComplication(z2Min = z2Min, goalMin = goal, tapAction = tapAction)
    }

    private fun buildRingComplication(
        z2Min: Int,
        goalMin: Int,
        tapAction: PendingIntent?
    ): SmallImageComplicationData {
        val activeBitmap = Zone2RingRenderer.renderFullRingBitmap(z2Min, goalMin, sizePx = 192, ambient = false)
        val ambientBitmap = Zone2RingRenderer.renderFullRingBitmap(z2Min, goalMin, sizePx = 192, ambient = true)

        val smallImage = SmallImage.Builder(
            image = Icon.createWithBitmap(activeBitmap),
            type = SmallImageType.PHOTO
        )
            .setAmbientImage(Icon.createWithBitmap(ambientBitmap))
            .build()

        return SmallImageComplicationData.Builder(
            smallImage = smallImage,
            contentDescription = PlainComplicationText.Builder("Soma Zone 2: $z2Min of $goalMin min").build()
        )
            .setTapAction(tapAction)
            .build()
    }
}
