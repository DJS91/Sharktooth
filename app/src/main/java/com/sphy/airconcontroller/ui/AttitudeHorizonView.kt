package com.sphy.airconcontroller.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Circular vehicle inclinometer, with a Shark photo and ground that tilt together. */
class AttitudeHorizonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    enum class AxisMode { PITCH, ROLL }

    private val front by lazy { requireNotNull(BitmapFactory.decodeResource(resources, R.drawable.shark_front)) }
    private val side by lazy { requireNotNull(BitmapFactory.decodeResource(resources, R.drawable.shark_side)) }
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            120f, 136f, 106f,
            ContextCompat.getColor(context, R.color.attitude_face),
            ContextCompat.getColor(context, R.color.attitude_edge),
            Shader.TileMode.CLAMP,
        )
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.attitude_rim)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.attitude_ticks)
        style = Paint.Style.STROKE
        strokeWidth = 1.3f
    }
    private val majorTickPaint = Paint(tickPaint).apply {
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.attitude_accent)
    }
    private val levelPaint = Paint(accentPaint).apply {
        alpha = 150
        strokeWidth = 1.5f
    }
    private val titlePaint = Paint(accentPaint).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val numberPaint = Paint(accentPaint).apply {
        textAlign = Paint.Align.CENTER
        textSize = 26f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val scalePaint = Paint(tickPaint).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = 13f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val photoRect = RectF()
    private val clipPath = Path().apply { addCircle(120f, 136f, 94f, Path.Direction.CW) }
    private val pointer = Path().apply {
        moveTo(120f, 45f)
        lineTo(115f, 36f)
        lineTo(125f, 36f)
        close()
    }
    private var displayAngle = 0f
    private var targetAngle = 0f
    private var reading = "\u2014"
    var axisMode: AxisMode = AxisMode.ROLL
        set(value) {
            if (field == value) return
            field = value
            setAttitude(null, null)
        }

    fun setAttitude(pitchDeg: Double?, rollDeg: Double?) {
        var angle = if (axisMode == AxisMode.PITCH) pitchDeg else rollDeg
        if (angle != null && !angle.isFinite()) {
            Log.w("AttitudeHorizonView", "Non-finite $axisMode reading: $angle")
            angle = null
        }
        reading = angle?.let { context.getString(R.string.vehicle_info_attitude_angle_fmt, it) } ?: "\u2014"
        val description = context.getString(
            if (axisMode == AxisMode.PITCH) R.string.vehicle_info_pitch_fmt else R.string.vehicle_info_roll_fmt,
            angle?.let { String.format(resources.configuration.locales[0], "%.1f", it) } ?: "\u2014",
        )
        if (contentDescription != description) contentDescription = description
        targetAngle = (angle ?: 0.0).coerceIn(-45.0, 45.0).toFloat()
        removeCallbacks(ticker)
        if (isAttachedToWindow) {
            postOnAnimation(ticker)
        } else {
            displayAngle = targetAngle
            invalidate()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val delta = targetAngle - displayAngle
            if (abs(delta) < 0.05f) {
                displayAngle = targetAngle
                invalidate()
                return
            }
            displayAngle += delta * 0.18f
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setAttitude(null, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val preferred = (180 * resources.displayMetrics.density).toInt()
        val w = resolveSize(preferred + paddingLeft + paddingRight, widthMeasureSpec)
        val h = resolveSize(
            ((w - paddingLeft - paddingRight) * 252f / 240f).toInt() + paddingTop + paddingBottom,
            heightMeasureSpec,
        )
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        val scale = minOf(availableWidth / 240f, availableHeight / 252f)
        if (scale <= 0f) return
        canvas.save()
        canvas.translate(
            paddingLeft + (availableWidth - 240f * scale) / 2f,
            paddingTop + (availableHeight - 252f * scale) / 2f,
        )
        canvas.scale(scale, scale)
        val title = context.getString(
            if (axisMode == AxisMode.PITCH) R.string.vehicle_info_pitch_label else R.string.vehicle_info_roll_label,
        ).uppercase(resources.configuration.locales[0])
        canvas.drawText(title, 120f, 17f, titlePaint)
        canvas.drawCircle(120f, 136f, 108f, facePaint)
        canvas.drawCircle(120f, 136f, 108f, rimPaint)

        for (degrees in 0 until 360 step 3) {
            val rad = Math.toRadians(degrees.toDouble())
            val major = degrees % 15 == 0
            val inner = if (major) 94f else 100f
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            canvas.drawLine(120f + c * inner, 136f + s * inner, 120f + c * 105f, 136f + s * 105f,
                if (major) majorTickPaint else tickPaint)
        }
        for (degrees in -45..45 step 15) {
            val rad = Math.toRadians(degrees - 90.0)
            canvas.drawText(abs(degrees).toString(),
                120f + cos(rad).toFloat() * 78f, 139f + sin(rad).toFloat() * 78f, scalePaint)
        }
        canvas.drawCircle(120f, 29f, 2f, accentPaint)
        canvas.drawLine(8f, 136f, 17f, 136f, accentPaint)
        canvas.drawLine(223f, 136f, 232f, 136f, accentPaint)
        canvas.save()
        canvas.rotate(displayAngle, 120f, 136f)
        canvas.drawPath(pointer, accentPaint)
        canvas.restore()

        val photo = if (axisMode == AxisMode.PITCH) side else front
        val photoWidth = if (axisMode == AxisMode.PITCH) 146f else 83.2f
        val photoHeight = photoWidth * photo.height / photo.width
        photoRect.set(120f - photoWidth / 2, 126f - photoHeight / 2,
            120f + photoWidth / 2, 126f + photoHeight / 2)
        // The front photo has 19 transparent rows below the tyres.
        val groundY = photoRect.bottom - if (axisMode == AxisMode.ROLL) photoHeight * 19f / 768f else 0f
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.rotate(displayAngle, 120f, 126f)
        canvas.drawLine(-120f, groundY, 360f, groundY, levelPaint)
        canvas.drawBitmap(photo, null, photoRect, photoPaint)
        canvas.restore()
        canvas.drawText(reading, 120f, 225f - 5f / scale - 252f * 0.1f, numberPaint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        displayAngle = targetAngle
        super.onDetachedFromWindow()
    }
}
