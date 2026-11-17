package com.sphy.airconcontroller.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class ColorBarThumb(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Path()
    private var selectedColor = Color.WHITE
    private var drawableAlpha = 255
    private var filter: ColorFilter? = null

    fun setColor(color: Int) {
        if (selectedColor == color) return
        selectedColor = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(density, density)
        paint.colorFilter = filter
        paint.style = Paint.Style.FILL
        paint.setColorWithAlpha(Color.WHITE)
        arrow.reset()
        arrow.moveTo(11f, 16f)
        arrow.lineTo(21f, 16f)
        arrow.lineTo(16f, 22f)
        arrow.close()
        canvas.drawPath(arrow, paint)
        paint.setColorWithAlpha(selectedColor)
        canvas.drawCircle(16f, 10f, 8f, paint)
        paint.setColorWithAlpha(Color.WHITE)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(16f, 10f, 8f, paint)
        canvas.restoreToCount(saved)
    }

    override fun getIntrinsicWidth(): Int = (32f * density).toInt()
    override fun getIntrinsicHeight(): Int = (56f * density).toInt()
    override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun Paint.setColorWithAlpha(color: Int) {
        this.color = color
        alpha = Color.alpha(color) * drawableAlpha / 255
    }
}
