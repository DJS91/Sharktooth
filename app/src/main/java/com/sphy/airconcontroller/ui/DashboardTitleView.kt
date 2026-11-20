package com.sphy.airconcontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.AttributeSet
import com.google.android.material.textview.MaterialTextView

class DashboardTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : MaterialTextView(context, attrs, defStyleAttr) {
    private var solidHalo = false

    fun enableSolidHalo() {
        solidHalo = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (solidHalo && shadowRadius > 0f) {
            val style = paint.style
            val width = paint.strokeWidth
            val filter = paint.colorFilter
            val save = canvas.save()
            try {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f * resources.displayMetrics.density
                paint.colorFilter = PorterDuffColorFilter(shadowColor, PorterDuff.Mode.SRC_IN)
                super.onDraw(canvas)
            } finally {
                paint.style = style
                paint.strokeWidth = width
                paint.colorFilter = filter
                canvas.restoreToCount(save)
            }
        }
        super.onDraw(canvas)
    }
}
