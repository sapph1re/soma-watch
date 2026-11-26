package com.aisling.somawatch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface

/**
 * Renders crisp, anti-aliased circular progress ring Bitmaps for Wear OS complications.
 * Used both as a full-size SMALL_IMAGE complication ("Soma Cardio Ring") and as a mini
 * progress donut icon inside SHORT_TEXT slots when a watch face doesn't support RANGED_VALUE.
 */
object Zone2RingRenderer {

    fun renderFullRingBitmap(
        z2Min: Int,
        goalMin: Int,
        sizePx: Int = 192,
        ambient: Boolean = false,
        centerLabel: String = "LOAD"
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f
        val cy = sizePx / 2f

        val strokeWidth = if (ambient) sizePx * 0.065f else sizePx * 0.085f
        val inset = strokeWidth / 2f + sizePx * 0.055f
        val oval = RectF(inset, inset, sizePx - inset, sizePx - inset)

        // Subtle circular background track
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            color = if (ambient) 0x33FFFFFF else 0x38FFFFFF
        }
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)

        // Progress arc (0..100%)
        val safeGoal = goalMin.coerceAtLeast(1)
        val fraction = (z2Min.toFloat() / safeGoal.toFloat()).coerceIn(0f, 1f)
        if (z2Min > 0) {
            val sweep = (fraction * 360f).coerceAtLeast(10f)
            val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                strokeCap = Paint.Cap.ROUND
                if (ambient) {
                    color = 0xCCFFFFFF.toInt()
                } else if (fraction >= 1f) {
                    color = 0xFF34D399.toInt()
                } else {
                    val gradient = SweepGradient(
                        cx,
                        cy,
                        intArrayOf(0xFF059669.toInt(), 0xFF10B981.toInt(), 0xFF34D399.toInt()),
                        floatArrayOf(0f, 0.55f, 1f)
                    )
                    val matrix = Matrix().apply { preRotate(-90f, cx, cy) }
                    gradient.setLocalMatrix(matrix)
                    shader = gradient
                }
            }
            canvas.drawArc(oval, -90f, sweep, false, arcPaint)
        }

        // Prominent Zone 2 minutes number in center (without /150)
        val valueText = z2Min.toString()
        val valueTextSize = when {
            valueText.length >= 3 -> sizePx * 0.28f
            else -> sizePx * 0.34f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ambient) 0xDDFFFFFF.toInt() else 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            textSize = valueTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val valueMetrics = valuePaint.fontMetrics
        val valueCenterY = cy - sizePx * 0.04f
        val valueBaseline = valueCenterY - (valueMetrics.ascent + valueMetrics.descent) / 2f
        canvas.drawText(valueText, cx, valueBaseline, valuePaint)

        // Dim secondary label underneath (e.g. "LOAD")
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ambient) 0x88FFFFFF.toInt() else 0xFF94A3B8.toInt()
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.145f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelMetrics = labelPaint.fontMetrics
        val labelCenterY = cy + sizePx * 0.23f
        val labelBaseline = labelCenterY - (labelMetrics.ascent + labelMetrics.descent) / 2f
        canvas.drawText(centerLabel, cx, labelBaseline, labelPaint)

        return bitmap
    }

    fun renderMiniRingBitmap(
        z2Min: Int,
        goalMin: Int,
        sizePx: Int = 96,
        ambient: Boolean = false
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f
        val cy = sizePx / 2f

        val strokeWidth = sizePx * 0.16f
        val inset = strokeWidth / 2f + sizePx * 0.08f
        val oval = RectF(inset, inset, sizePx - inset, sizePx - inset)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            color = if (ambient) 0x44FFFFFF else 0x48FFFFFF
        }
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)

        val safeGoal = goalMin.coerceAtLeast(1)
        val fraction = (z2Min.toFloat() / safeGoal.toFloat()).coerceIn(0f, 1f)
        if (z2Min > 0) {
            val sweep = (fraction * 360f).coerceAtLeast(16f)
            val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                strokeCap = Paint.Cap.ROUND
                color = when {
                    ambient -> 0xDDFFFFFF.toInt()
                    fraction >= 1f -> 0xFF34D399.toInt()
                    else -> 0xFF10B981.toInt()
                }
            }
            canvas.drawArc(oval, -90f, sweep, false, arcPaint)
        }

        if (fraction >= 1f && !ambient) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = 0xFF34D399.toInt()
            }
            canvas.drawCircle(cx, cy, sizePx * 0.11f, dotPaint)
        }

        return bitmap
    }
}
