package com.sphy.airconcontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.sphy.airconcontroller.R
import java.text.NumberFormat
import kotlin.math.ceil

internal class EngineRpmHistory {
    data class Sample(val timeMs: Long, val rpm: Double?)

    private val readings = ArrayDeque<Sample>()

    fun record(timeMs: Long, rpm: Double?) {
        while (readings.firstOrNull()?.let { it.timeMs < timeMs - MAX_HISTORY_MS } == true) {
            readings.removeFirst()
        }
        readings.addLast(Sample(timeMs, rpm?.takeIf { it.isFinite() && it >= 0 }))
    }

    fun since(timeMs: Long, rangeMs: Long): List<Sample> =
        readings.asReversed().takeWhile { it.timeMs >= timeMs - rangeMs }.asReversed()

    companion object {
        const val TEN_SECONDS_MS = 10_000L
        const val FIVE_MINUTES_MS = 300_000L
        const val MAX_HISTORY_MS = 1_800_000L
    }
}

internal class EngineRpmGraph(context: Context, private val rangeMs: Long = EngineRpmHistory.TEN_SECONDS_MS) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_muted)
        textSize = 11 * resources.displayMetrics.scaledDensity
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.surface_stroke)
        strokeWidth = density
    }
    private val numberFormat = NumberFormat.getIntegerInstance(resources.configuration.locales[0])
    private val timeLabels = if (rangeMs == EngineRpmHistory.TEN_SECONDS_MS) listOf(
        context.getString(R.string.dashboard_rpm_seconds_ago, 10),
        context.getString(R.string.dashboard_rpm_seconds_ago, 5),
        context.getString(R.string.dashboard_rpm_now),
    ) else listOf(
        context.getString(R.string.dashboard_rpm_minutes_ago, (rangeMs / 60_000).toInt()),
        context.getString(R.string.dashboard_rpm_minutes_fraction_ago,
            NumberFormat.getNumberInstance(resources.configuration.locales[0]).format(rangeMs / 120_000.0)),
        context.getString(R.string.dashboard_rpm_now),
    )
    private val path = Path()
    private var samples = emptyList<EngineRpmHistory.Sample>()
    private var now = 0L
    private var maxGapMs = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(samples: List<EngineRpmHistory.Sample>, now: Long, maxGapMs: Long) {
        this.samples = samples
        this.now = now
        this.maxGapMs = maxGapMs
        contentDescription = context.getString(
            if (samples.any { it.rpm != null }) when (rangeMs) {
                EngineRpmHistory.TEN_SECONDS_MS -> R.string.dashboard_rpm_last_ten_seconds
                EngineRpmHistory.FIVE_MINUTES_MS -> R.string.dashboard_rpm_last_five_minutes
                else -> R.string.dashboard_rpm_last_thirty_minutes
            } else R.string.dashboard_rpm_no_history,
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val start = now - rangeMs
        val peak = samples.asSequence().filter { it.timeMs in start..now }.maxOfOrNull { it.rpm ?: 0.0 } ?: 0.0
        val step = (ceil(peak / 2_000) * 500).coerceAtLeast(500.0)
        val scale = step * 4
        val gap = 6 * density
        val left = label.measureText(numberFormat.format(scale)) + gap
        val right = width - paint.strokeWidth
        val top = (label.descent() - label.ascent()) / 2 + paint.strokeWidth
        val bottom = height - label.fontSpacing - gap
        if (right <= left || bottom <= top) return

        label.textAlign = Paint.Align.RIGHT
        for (tick in 0..4) {
            val y = bottom - (bottom - top) * tick / 4
            canvas.drawLine(left, y, right, y, grid)
            canvas.drawText(numberFormat.format(step * tick), left - gap, y - (label.ascent() + label.descent()) / 2, label)
        }
        val middle = (left + right) / 2
        val showMidpoint = (right - left) / 2 >
            maxOf(label.measureText(timeLabels.first()), label.measureText(timeLabels.last())) +
            label.measureText(timeLabels[1]) / 2 + gap
        for ((index, x) in listOf(left, middle, right).withIndex()) {
            if (index == 1 && !showMidpoint) continue
            canvas.drawLine(x, top, x, bottom, grid)
            label.textAlign = when (index) {
                0 -> Paint.Align.LEFT
                1 -> Paint.Align.CENTER
                else -> Paint.Align.RIGHT
            }
            canvas.drawText(timeLabels[index], x, height - label.descent(), label)
        }
        paint.color = context.getColor(R.color.dashboard_engine)
        path.rewind()
        var previous: Long? = null
        for (sample in samples) {
            val rpm = sample.rpm
            if (sample.timeMs !in start..now || rpm == null) {
                previous = null
                continue
            }
            val x = left + (right - left) * (sample.timeMs - start) / rangeMs
            val y = bottom - (bottom - top) * (rpm / scale).toFloat()
            val last = previous
            if (last == null || sample.timeMs - last > maxGapMs) {
                path.moveTo(x, y)
                canvas.drawPoint(x, y, paint)
            } else {
                path.lineTo(x, y)
            }
            previous = sample.timeMs
        }
        paint.style = Paint.Style.STROKE
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
}
