package com.sphy.airconcontroller.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.sphy.airconcontroller.R
import kotlin.math.max
import kotlin.math.min

/** Rounded track with an animated solid-color fill for 0–100% values. */
class PercentBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_stroke)
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_orange)
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var startPct: Float? = null
    private var displayPct = 0f
    private var targetPct = 0f

    fun setFillColor(@ColorInt color: Int, glowRadius: Float = 0f) {
        fillPaint.color = color
        val startAlpha = if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_NO) 100 else 45
        startPaint.color = ColorUtils.setAlphaComponent(color, startAlpha)
        if (glowRadius > 0f) {
            fillPaint.setShadowLayer(glowRadius, 0f, 0f, ColorUtils.setAlphaComponent(color, 170))
        } else {
            fillPaint.clearShadowLayer()
        }
        invalidate()
    }

    fun setStartPercent(value: Double?) {
        val next = value?.takeIf { it.isFinite() && it in 0.0..100.0 }?.toFloat()
        if (startPct == next) return
        startPct = next
        invalidate()
    }

    fun setPercent(value: Double?, animate: Boolean = true) {
        val next = value?.toFloat()?.coerceIn(0f, 100f) ?: 0f
        targetPct = next
        if (!animate) {
            displayPct = next
            invalidate()
            return
        }
        postOnAnimation(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val delta = targetPct - displayPct
            if (kotlin.math.abs(delta) < 0.3f) {
                displayPct = targetPct
                invalidate()
                return
            }
            displayPct += delta * 0.25f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val h = bottom - top
        val w = right - left
        val radius = h / 2f
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        startPct?.let { pct ->
            rect.set(left, top, left + w * pct / 100f, bottom)
            canvas.drawRoundRect(rect, radius, radius, startPaint)
        }

        val fillW = max(0f, min(w, w * displayPct / 100f))
        if (fillW <= 0f) return
        rect.set(left, top, left + fillW, bottom)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
}
