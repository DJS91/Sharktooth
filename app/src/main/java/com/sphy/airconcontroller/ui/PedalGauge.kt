package com.sphy.airconcontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.sphy.airconcontroller.R
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin

internal class PedalGauge(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val arc = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        textAlign = Paint.Align.CENTER
    }
    private val colours = intArrayOf(
        context.getColor(R.color.dashboard_accent_blue),
        context.getColor(R.color.dashboard_brake),
    )
    private val track = context.getColor(R.color.surface_stroke)
    private val labels = arrayOf(
        context.getString(R.string.dashboard_pedal_accelerate),
        context.getString(R.string.vehicle_info_row_brake),
    )
    private val values = arrayOfNulls<Double>(2)
    private val peaks = arrayOfNulls<Float>(2)
    private val textColor = context.getColor(R.color.text_primary)
    private val captionColor = context.getColor(R.color.text_muted)
    private var reading: String? = null
    private var label = context.getString(R.string.dashboard_pedals)
    private var activePedal = false
    var maxHeight = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    fun set(accelerator: Double?, brake: Double?) {
        val first = accelerator.validPercent()
        val second = brake.validPercent()
        if (values[0] == first && values[1] == second) return
        values[0] = first
        values[1] = second
        val active = when {
            second != null && second > 0 -> 1
            first != null && first > 0 -> 0
            else -> null
        }
        activePedal = active != null
        label = active?.let { labels[it] } ?: context.getString(R.string.dashboard_pedals)
        val percent = active?.let { values[it] } ?: if (first == 0.0 && second == 0.0) 0.0 else null
        reading = percent?.let { String.format("%.0f", it) }
        updateDescription()
        invalidate()
    }

    private fun Double?.validPercent() = this?.takeIf { it.isFinite() && it in 0.0..100.0 }

    fun setPeaks(accelerator: Float?, brake: Float?) {
        if (peaks[0] == accelerator && peaks[1] == brake) return
        peaks[0] = accelerator
        peaks[1] = brake
        invalidate()
    }

    private fun updateDescription() {
        val spokenReading = reading?.let { "$it%" } ?: context.getString(R.string.dashboard_value_unavailable)
        contentDescription = "$label: $spokenReading"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val preferred = (256 * density).roundToInt()
        val width = resolveSize(preferred, widthMeasureSpec)
        val height = (minOf(width, preferred) * ARC_HEIGHT_RATIO).roundToInt()
        setMeasuredDimension(width, resolveSize(minOf(height, maxHeight), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val size = minOf(width.toFloat(), height / ARC_HEIGHT_RATIO, 256 * density)
        val cy = size / 2f
        val stroke = minOf(9f * density, size * 0.065f)
        if (stroke <= 0f) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        for (i in values.indices) {
            val r = size / 2 - stroke * (1 + i * 1.5f)
            arc.set(cx - r, cy - r, cx + r, cy + r)
            paint.color = track
            canvas.drawArc(arc, 135f, 270f, false, paint)
            val percent = values[i] ?: continue
            if (percent > 0) {
                paint.color = colours[i]
                canvas.drawArc(arc, 135f, (270 * percent / 100).toFloat(), false, paint)
            }
        }
        paint.style = Paint.Style.FILL
        for (i in peaks.indices) {
            val peak = peaks[i] ?: continue
            val radius = size / 2 - stroke * (1 + i * 1.5f)
            val angle = Math.toRadians(135.0 + 270 * peak / 100)
            val x = cx + radius * cos(angle).toFloat()
            val y = cy + radius * sin(angle).toFloat()
            val dotRadius = stroke * 0.42f
            paint.color = colours[i]
            canvas.drawCircle(x, y, dotRadius, paint)
        }
        paint.typeface = Typeface.DEFAULT_BOLD
        drawReading(canvas, cx, cy + size * 0.035f, size * 0.2024f)
        paint.color = textColor
        paint.typeface = Typeface.DEFAULT
        paint.textSize = size * 0.0726f
        val labelBaseline = cy + size * 0.19f
        canvas.drawText(label, cx, labelBaseline, paint)
        if (activePedal) {
            val captionBaseline = labelBaseline + paint.textSize * 1.1f
            paint.textSize *= 0.8f
            paint.color = captionColor
            canvas.drawText(
                context.getString(R.string.dashboard_pedal_label),
                cx,
                captionBaseline,
                paint,
            )
        }
    }

    private fun drawReading(canvas: Canvas, cx: Float, baseline: Float, numberSize: Float) {
        paint.textSize = numberSize
        val number = reading
        if (number == null) {
            drawReadingText(canvas, context.getString(R.string.dashboard_value_unavailable), cx, baseline)
            return
        }

        val numberWidth = paint.measureText(number)
        val unitSize = numberSize * 0.55f
        val gap = numberSize * 0.08f
        paint.textSize = unitSize
        val unitWidth = paint.measureText("%")
        paint.textSize = numberSize
        drawReadingText(canvas, number, cx, baseline)
        paint.textSize = unitSize
        drawReadingText(canvas, "%", cx + numberWidth / 2 + gap + unitWidth / 2, baseline - numberSize * 0.32f)
    }

    private fun drawReadingText(canvas: Canvas, text: String, x: Float, baseline: Float) {
        paint.color = textColor
        canvas.drawText(text, x, baseline, paint)
    }

    private companion object {
        // The open base of the 270-degree arc does not need a full square.
        const val ARC_HEIGHT_RATIO = 0.86f
    }
}
