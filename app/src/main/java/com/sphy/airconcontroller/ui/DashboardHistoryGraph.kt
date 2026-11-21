package com.sphy.airconcontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.sphy.airconcontroller.R
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

internal class DashboardHistoryGraph(
    context: Context,
    private val key: String,
    private val title: String,
    private val maxGapMs: Long = DashboardHistory.GAP_MS,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * resources.displayMetrics.scaledDensity
        color = context.getColor(R.color.text_muted)
    }
    private val grid = Paint().apply { color = context.getColor(R.color.surface_stroke); strokeWidth = density }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(if (key == "regen") R.color.tyre_ok else R.color.dashboard_accent_blue)
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 3 }
    private val time = DateTimeFormatter.ofPattern("HH:mm", resources.configuration.locales[0])
    private var samples = emptyList<DashboardHistory.Sample>()
    private var now = 0L

    fun bind(samples: List<DashboardHistory.Sample>, now: Long) {
        this.samples = samples
        this.now = now
        val count = samples.count { it.values[key] != null }
        contentDescription = context.getString(R.string.dashboard_history_description, title, count)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val (start, end) = DashboardHistory.dayBounds(now)
        val valid = samples.filter { it.time in start..now }.mapNotNull { it.values[key] }.filter { it.isFinite() }
        val low = minOf(0.0, valid.minOrNull() ?: 0.0)
        val high = maxOf(low + 1.0, valid.maxOrNull()?.let { it + abs(it) * 0.05 } ?: 1.0)
        val left = maxOf(label.measureText(number.format(high)), label.measureText(number.format(low))) + 8 * density
        val right = width - 4 * density
        val top = label.fontSpacing
        val bottom = height - label.fontSpacing * 1.5f
        if (right <= left || bottom <= top) return
        for (tick in 0..4) {
            val y = bottom - (bottom - top) * tick / 4
            canvas.drawLine(left, y, right, y, grid)
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText(number.format(low + (high - low) * tick / 4), left - 6 * density,
                y - (label.ascent() + label.descent()) / 2, label)
        }
        for (tick in 0..2) {
            val x = left + (right - left) * tick / 2
            val epoch = start + (end - start) * tick / 2
            label.textAlign = when (tick) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            canvas.drawText(time.format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())),
                x, height - label.descent(), label)
        }
        if (valid.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            canvas.drawText(context.getString(R.string.dashboard_history_empty), (left + right) / 2,
                (top + bottom) / 2, label)
            return
        }
        val path = Path()
        var previous: Long? = null
        samples.forEach { sample ->
            val value = sample.values[key]
            if (sample.time !in start..now || value == null || !value.isFinite()) {
                previous = null
            } else {
                val x = left + (right - left) * (sample.time - start).toFloat() / (end - start)
                val y = bottom - (bottom - top) * ((value - low) / (high - low)).toFloat()
                if (previous == null || sample.time - checkNotNull(previous) > maxGapMs) {
                    path.moveTo(x, y)
                    canvas.drawPoint(x, y, line)
                } else path.lineTo(x, y)
                previous = sample.time
            }
        }
        canvas.drawPath(path, line)
    }
}
