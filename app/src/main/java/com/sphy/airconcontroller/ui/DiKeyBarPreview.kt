package com.sphy.airconcontroller.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.sphy.airconcontroller.R
import com.sphy.airconcontroller.dikey.DialDisplay
import com.sphy.airconcontroller.dikey.DialDisplayType
import com.sphy.airconcontroller.dikey.DialDisplays
import com.sphy.airconcontroller.dikey.DiKeyProtocol
import kotlin.math.hypot
import kotlin.math.roundToInt

class DiKeyBarPreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val source = requireNotNull(BitmapFactory.decodeResource(resources, R.drawable.dikey_bar_preview))
    private val bitmap = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
    private val originalPixels = IntArray(source.width * source.height).also {
        source.getPixels(it, 0, source.width, 0, 0, source.width, source.height)
    }
    private val pixels = originalPixels.clone()
    private val barColors = IntArray(3)
    private var keyColor = 0
    private var dialDisplays = DialDisplays()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.OUTER)
    }
    private val keyGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.OUTER)
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val destination = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setBarColor(bar: Int, red: Int, green: Int, blue: Int) {
        require(bar in 1..3)
        val color = Color.rgb(red, green, blue)
        if (barColors[bar - 1] == color) return
        barColors[bar - 1] = color

        tintRegion(BAR_REGIONS[bar - 1], red, green, blue, isBar = true, roundedBounds = LIGHT_BOUNDS[bar - 1])
        invalidate()
    }

    fun setKeyBacklightColor(red: Int, green: Int, blue: Int) {
        val color = Color.rgb(red, green, blue)
        if (keyColor == color) return
        keyColor = color
        KEY_REGIONS.forEach { tintRegion(it, red, green, blue, isBar = false) }
        invalidate()
    }

    fun setDialDisplays(displays: DialDisplays) {
        if (dialDisplays == displays) return
        dialDisplays = displays
        invalidate()
    }

    private fun tintRegion(
        region: Rect,
        red: Int,
        green: Int,
        blue: Int,
        isBar: Boolean,
        roundedBounds: RectF? = null,
    ) {
        for (y in region.top until region.bottom) {
            for (x in region.left until region.right) {
                val index = y * source.width + x
                val pixel = originalPixels[index]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val isLight = if (isBar) {
                    g >= 45 && g > r + 10 && g > b + 8
                } else {
                    b > 80 && b > r + 20 && b > g + 20
                }
                if (isLight) {
                    val base = if (isBar) minOf(r, b) else minOf(r, g)
                    val coverage = if (roundedBounds != null) roundedCoverage(x, y, roundedBounds) else 1f
                    val light = (((if (isBar) g else b) - base) * coverage).roundToInt()
                    pixels[index] = Color.argb(
                        Color.alpha(pixel),
                        base + light * red / 255,
                        base + light * green / 255,
                        base + light * blue / 255,
                    )
                }
            }
        }
        bitmap.setPixels(
            pixels, region.top * source.width + region.left, source.width,
            region.left, region.top, region.width(), region.height(),
        )
    }

    private fun roundedCoverage(x: Int, y: Int, bounds: RectF): Float {
        val px = x + 0.5f
        val py = y + 0.5f
        if (!bounds.contains(px, py)) return 1f
        val dx = maxOf(bounds.left + BAR_RADIUS - px, px - (bounds.right - BAR_RADIUS), 0f)
        val dy = maxOf(bounds.top + BAR_RADIUS - py, py - (bounds.bottom - BAR_RADIUS), 0f)
        if (dx == 0f || dy == 0f) return 1f
        return (BAR_RADIUS + 0.5f - hypot(dx, dy)).coerceIn(0f, 1f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize(bitmap.width + paddingLeft + paddingRight, widthMeasureSpec)
        val imageWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val desiredHeight = (imageWidth.toFloat() * bitmap.height / bitmap.width).toInt() +
            paddingTop + paddingBottom
        setMeasuredDimension(measuredWidth, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        val scale = minOf(availableWidth.toFloat() / bitmap.width, availableHeight.toFloat() / bitmap.height)
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        val left = paddingLeft + (availableWidth - imageWidth) / 2f
        val top = paddingTop + (availableHeight - imageHeight) / 2f
        destination.set(left, top, left + imageWidth, top + imageHeight)
        canvas.drawBitmap(bitmap, null, destination, paint)
        if (scale <= 0f) return
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.save()
        canvas.clipRect(0f, 0f, bitmap.width.toFloat(), 70f)
        barColors.forEachIndexed { index, color ->
            glowPaint.color = glowColor(color)
            if (Color.alpha(glowPaint.color) > 0) {
                canvas.drawRoundRect(LIGHT_BOUNDS[index], BAR_RADIUS, BAR_RADIUS, glowPaint)
            }
        }
        canvas.restore()
        canvas.save()
        canvas.clipRect(0f, 69f, bitmap.width.toFloat(), 106f)
        keyGlowPaint.color = glowColor(keyColor)
        if (Color.alpha(keyGlowPaint.color) > 0) {
            KEY_REGIONS.forEach { region ->
                val keyLeft = region.left + 4f
                val keyRight = region.right - 3f
                canvas.drawRoundRect(keyLeft, 75f, keyRight, 84f, 2f, 2f, keyGlowPaint)
                canvas.drawRoundRect(keyLeft, 90f, keyRight, 99f, 2f, 2f, keyGlowPaint)
            }
        }
        canvas.restore()
        drawDial(canvas, 464f, dialDisplays.left)
        drawDial(canvas, 1648f, dialDisplays.right)
        canvas.restore()
    }

    private fun drawDial(canvas: Canvas, centerX: Float, display: DialDisplay?) {
        if (display == null || display.displayType == DiKeyProtocol.DISPLAY_TYPE_BLANK || display.value !in 0..99) return
        val isTemperature = display.displayType == DialDisplayType.DRIVER_TEMP.code ||
            display.displayType == DialDisplayType.PASSENGER_TEMP.code
        var x = centerX - if (display.value < 10) 15f else 34f
        if (isTemperature) x -= 19f
        if (display.value >= 10) {
            drawSegments(canvas, x, DIGIT_SEGMENTS[display.value / 10])
            x += 38f
        }
        drawSegments(canvas, x, DIGIT_SEGMENTS[display.value % 10])
        if (isTemperature) drawSegments(canvas, x + 38f, DEGREE_SEGMENTS)
    }

    private fun drawSegments(canvas: Canvas, x: Float, segments: Int) {
        fun line(bit: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
            if (segments and (1 shl bit) != 0) canvas.drawLine(x + x1, y1, x + x2, y2, digitPaint)
        }
        line(0, 5f, 68f, 25f, 68f)
        line(1, 28f, 72f, 28f, 91f)
        line(2, 28f, 99f, 28f, 118f)
        line(3, 5f, 122f, 25f, 122f)
        line(4, 2f, 99f, 2f, 118f)
        line(5, 2f, 72f, 2f, 91f)
        line(6, 5f, 95f, 25f, 95f)
    }

    private fun glowColor(color: Int): Int {
        val brightness = maxOf(Color.red(color), Color.green(color), Color.blue(color))
        if (brightness == 0) return Color.TRANSPARENT
        return Color.argb(
            brightness * 140 / 255,
            Color.red(color) * 255 / brightness,
            Color.green(color) * 255 / brightness,
            Color.blue(color) * 255 / brightness,
        )
    }

    companion object {
        private const val BAR_RADIUS = 6f
        private const val DEGREE_SEGMENTS = 0b1100011
        private val DIGIT_SEGMENTS = intArrayOf(63, 6, 91, 79, 102, 109, 125, 7, 127, 111)
        // Pixel bounds in the supplied, unscaled 2114 x 190 image.
        private val BAR_REGIONS = arrayOf(
            Rect(44, 40, 362, 60),
            Rect(575, 40, 1531, 60),
            Rect(1750, 40, 2068, 60),
        )
        private val LIGHT_BOUNDS = arrayOf(
            RectF(54f, 44f, 351f, 57f),
            RectF(585f, 44f, 1521f, 57f),
            RectF(1760f, 44f, 2057f, 57f),
        )
        private val KEY_REGIONS = arrayOf(
            Rect(82, 74, 155, 100),
            Rect(244, 74, 315, 100),
            Rect(613, 74, 684, 100),
            Rect(770, 74, 842, 100),
            Rect(938, 74, 1010, 100),
            Rect(1099, 74, 1172, 100),
            Rect(1259, 74, 1332, 100),
            Rect(1419, 74, 1490, 100),
            Rect(1789, 74, 1862, 100),
            Rect(1955, 74, 2027, 100),
        )
    }
}
