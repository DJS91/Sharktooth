package com.sphy.airconcontroller.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.sphy.airconcontroller.R
import java.text.NumberFormat
import kotlin.math.min
import kotlin.math.roundToInt

internal class PowerFlowView(context: Context) : View(context) {
    private val truck = requireNotNull(context.getDrawable(R.drawable.power_flow_photo)).mutate().apply {
        setBounds(0, 0, WIDTH, HEIGHT)
        if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO) {
            alpha = (255 * 0.7f).roundToInt()
        }
    }
    // Positions are in the cropped photo's pixel coordinates.
    private val rearMotor = PointF(145f, 122f)
    private val frontMotor = PointF(360f, 268f)
    private val rearFarWheel = PointF(209f, 89f)
    private val rear = Axle(
        feed = Route(pointOnLine(rearMotor, frontMotor, 185f), rearMotor),
        near = Route(rearMotor, pointOnLine(rearMotor, rearFarWheel, 85f)),
        far = Route(rearMotor, rearFarWheel),
        motor = Path().apply {
            moveTo(119f, 130f)
            cubicTo(116f, 121f, 119f, 116f, 127f, 112f)
            lineTo(151f, 102f)
            cubicTo(160f, 98f, 168f, 105f, 169f, 115f)
            cubicTo(170f, 123f, 163f, 129f, 155f, 133f)
            lineTo(135f, 142f)
            cubicTo(127f, 145f, 122f, 139f, 119f, 130f)
            close()
        },
    )
    private val front = Axle(
        feed = Route(pointOnLine(rearMotor, frontMotor, 308f), frontMotor),
        near = Route(frontMotor, PointF(285f, 322f)),
        far = Route(frontMotor, PointF(426f, 222f)),
        motor = Path().apply {
            moveTo(333f, 283f)
            cubicTo(327f, 272f, 334f, 263f, 343f, 257f)
            lineTo(358f, 246f)
            cubicTo(371f, 237f, 382f, 245f, 388f, 257f)
            cubicTo(394f, 268f, 384f, 277f, 375f, 283f)
            lineTo(357f, 295f)
            cubicTo(347f, 299f, 337f, 293f, 333f, 283f)
            close()
        },
    )
    private val engineRoute = Route(pointOnLine(rearMotor, frontMotor, 410f), frontMotor)
    private val engineColor = context.getColor(R.color.dashboard_engine)
    private val batteryCell = BitmapFactory.decodeResource(resources, R.drawable.power_flow_battery_cell)
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cellMatrix = Matrix()
    private val cellTextureCorners = floatArrayOf(
        0f, 0f, batteryCell.width.toFloat(), 0f,
        batteryCell.width.toFloat(), batteryCell.height.toFloat(), 0f, batteryCell.height.toFloat(),
    )
    private val cellCorners = FloatArray(8)
    private val fill = Path()
    private val batteryOutline = Path().apply {
        moveTo(145f, 173f)
        lineTo(221f, 127f)
        lineTo(346f, 199f)
        lineTo(346f, 211f)
        lineTo(262f, 263f)
        lineTo(145f, 187f)
        close()
    }
    // Rasterize the static halos once rather than blurring a path on every animation frame.
    private val restingGlow by lazy { createBatteryGlow(6f, 75, 0x8031FF24.toInt()) }
    private val chargingGlow by lazy { createBatteryGlow(12f, 210, 0xD031FF24.toInt()) }
    private val pulseMatrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = 23f
    }
    private val numberFormat = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    private val percentFormat = NumberFormat.getPercentInstance()
    private val unavailable = context.getString(R.string.dashboard_value_unavailable)
    private var charge: Double? = null
    private var power: Double? = null
    private var rpm: Double? = null
    private var moving = false
    private var chargeText = unavailable
    private val visibleArea = Rect()

    fun bind(soc: Double?, powerKw: Double?, engineRpm: Double?, speedKmh: Double?) {
        val nextCharge = soc?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val nextPower = powerKw?.takeIf { it.isFinite() }
        val nextRpm = engineRpm?.takeIf { it.isFinite() && it >= 0.0 }
        val nextMoving = speedKmh != null && speedKmh.isFinite() && speedKmh != 0.0
        if (nextCharge == charge && nextPower == power && nextRpm == rpm && nextMoving == moving &&
            contentDescription != null) return
        charge = nextCharge
        power = nextPower
        rpm = nextRpm
        moving = nextMoving
        chargeText = nextCharge?.let { percentFormat.format(it / 100) } ?: unavailable
        val batteryStatus = when {
            nextPower == null -> R.string.dashboard_flow_unavailable
            nextPower > 0.0 -> R.string.dashboard_flow_using_power
            nextPower < 0.0 -> R.string.dashboard_flow_regen
            else -> R.string.dashboard_flow_idle
        }
        contentDescription = context.getString(
            R.string.dashboard_flow_description,
            chargeText,
            context.getString(batteryStatus),
            powerText(),
            rpmText(),
        )
        invalidate()
    }

    fun powerText(): String = power?.let { context.getString(
        R.string.dashboard_flow_kw, (if (it > 0.0) "+" else "") + numberFormat.format(it),
    ) } ?: unavailable

    fun rpmText(): String = rpm?.let { context.getString(
        R.string.dashboard_flow_rpm, numberFormat.format(it),
    ) } ?: unavailable

    var maxHeight = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val preferred = MAX_WIDTH_DP * density
        val width = resolveSize(preferred.roundToInt(), widthMeasureSpec)
        val diagramWidth = min(width.toFloat(), preferred)
        val diagramHeight = (diagramWidth * HEIGHT / WIDTH).roundToInt()
        setMeasuredDimension(width, resolveSize(min(diagramHeight, maxHeight), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val scale = min(width / WIDTH.toFloat(), height / HEIGHT.toFloat())
        if (scale <= 0f) return
        canvas.save()
        canvas.translate((width - WIDTH * scale) / 2f, (height - HEIGHT * scale) / 2f)
        canvas.scale(scale, scale)
        truck.draw(canvas)

        drawCharge(canvas)
        val powerKw = if (moving) power ?: 0.0 else 0.0
        val showEngineRoute = moving && (rpm ?: 0.0) > 0.0
        val drivetrainColor = when {
            powerKw > 0.0 -> DISCHARGE
            powerKw < 0.0 -> REGEN
            else -> null
        }
        val animate = drivetrainColor != null || showEngineRoute
        if (animate) {
            val phase = SystemClock.uptimeMillis() % FLOW_PERIOD_MS / FLOW_PERIOD_MS.toFloat()
            if (drivetrainColor != null) {
                drawAxle(canvas, rear, drivetrainColor, phase, reverse = powerKw < 0.0)
                drawAxle(canvas, front, drivetrainColor, phase, reverse = powerKw < 0.0)
            }
            if (showEngineRoute) drawRoute(canvas, engineRoute, engineColor, phase)
        }
        canvas.restore()

        // The flow is a live status indicator, not a system UI transition.
        if (animate && isShown && windowVisibility == VISIBLE &&
            getLocalVisibleRect(visibleArea)) {
            postInvalidateDelayed(33L)
        }
    }

    private fun drawCharge(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        val fraction = ((charge ?: 0.0) / 100).toFloat()
        val charging = (power ?: 0.0) < 0.0
        if (charging || fraction > 0f) {
            canvas.drawBitmap(if (charging) chargingGlow else restingGlow, 0f, 0f, cellPaint)
        }
        if (fraction > 0f) {
            // Match the top and the two visible side faces of the photographed pack.
            drawBatteryFace(canvas, BATTERY_FRONT, 262f, 249f, 346f, 199f, 346f, 211f, 262f, 263f)
            val nearX = 262 - 117 * fraction
            val nearY = 249 - 76 * fraction
            drawBatteryFace(canvas, BATTERY_SIDE, 262f, 249f, nearX, nearY, nearX, nearY + 14, 262f, 263f)
            for (index in 0 until BATTERY_CELLS) {
                val start = index.toFloat() / BATTERY_CELLS
                if (start >= fraction) break
                val end = min(fraction, (index + 1f) / BATTERY_CELLS)
                mapBatteryCell(start, end)
                canvas.drawBitmap(batteryCell, cellMatrix, cellPaint)
                if (index > 0) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.9f
                    paint.color = BATTERY_SEAM
                    canvas.drawLine(cellCorners[0], cellCorners[1], cellCorners[2], cellCorners[3], paint)
                    canvas.drawLine(cellCorners[0], cellCorners[1], cellCorners[0], cellCorners[1] + 13, paint)
                }
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.setShadowLayer(4f, 0f, 0f, 0xD0808080.toInt())
        canvas.drawText(chargeText, 247f, 194f, paint)
        paint.clearShadowLayer()
    }

    private fun createBatteryGlow(radius: Float, opacity: Int, shadowColor: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2f
            color = 0xFF31FF24.toInt()
            alpha = opacity
            setShadowLayer(radius, 0f, 0f, shadowColor)
        }
        Canvas(bitmap).apply {
            clipOutPath(batteryOutline)
            drawPath(batteryOutline, glowPaint)
        }
        return bitmap
    }

    private fun mapBatteryCell(start: Float, end: Float) {
        cellCorners[0] = 262 - 117 * start
        cellCorners[1] = 249 - 76 * start
        cellCorners[2] = 346 - 125 * start
        cellCorners[3] = 199 - 72 * start
        cellCorners[4] = 346 - 125 * end
        cellCorners[5] = 199 - 72 * end
        cellCorners[6] = 262 - 117 * end
        cellCorners[7] = 249 - 76 * end
        cellMatrix.setPolyToPoly(cellTextureCorners, 0, cellCorners, 0, 4)
    }

    private fun drawBatteryFace(
        canvas: Canvas, color: Int, x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float,
    ) {
        fill.rewind()
        fill.moveTo(x0, y0)
        fill.lineTo(x1, y1)
        fill.lineTo(x2, y2)
        fill.lineTo(x3, y3)
        fill.close()
        paint.color = color
        canvas.drawPath(fill, paint)
    }

    private fun drawAxle(canvas: Canvas, axle: Axle, color: Int, phase: Float, reverse: Boolean) {
        drawRoute(canvas, axle.feed, color, phase, reverse)
        drawRoute(canvas, axle.near, color, (phase + 0.5f) % 1f, reverse)
        drawRoute(canvas, axle.far, color, (phase + 0.5f) % 1f, reverse)
        paint.style = Paint.Style.FILL
        paint.color = (color and 0x00FFFFFF) or 0xB8000000.toInt()
        canvas.drawPath(axle.motor, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = color
        canvas.drawPath(axle.motor, paint)
    }

    private fun drawRoute(canvas: Canvas, route: Route, color: Int, phase: Float, reverse: Boolean = false) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 15f
        paint.color = color and 0x40FFFFFF
        canvas.drawPath(route.path, paint)
        paint.strokeWidth = 9f
        paint.color = color and 0x90FFFFFF.toInt()
        canvas.drawPath(route.path, paint)
        paint.strokeWidth = 5f
        paint.color = color
        canvas.drawPath(route.path, paint)
        val progress = if (reverse) 1f - phase else phase
        val center = (route.length + 2 * PULSE_RADIUS) * progress - PULSE_RADIUS
        pulseMatrix.setTranslate(route.dx * center, route.dy * center)
        route.highlight.setLocalMatrix(pulseMatrix)
        route.halo.setLocalMatrix(pulseMatrix)
        paint.color = Color.WHITE
        paint.strokeWidth = 8f
        paint.shader = route.halo
        canvas.drawPath(route.path, paint)
        paint.strokeWidth = 4f
        paint.shader = route.highlight
        canvas.drawPath(route.path, paint)
        paint.shader = null
    }

    private fun pointOnLine(start: PointF, end: PointF, x: Float) =
        PointF(x, start.y + (x - start.x) * (end.y - start.y) / (end.x - start.x))

    private class Route(start: PointF, end: PointF) {
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
        val length = PathMeasure(path, false).length
        val dx = (end.x - start.x) / length
        val dy = (end.y - start.y) / length
        val highlight = pulseGradient(start, 0x99FFFFFF.toInt())
        val halo = pulseGradient(start, 0x38FFFFFF)

        private fun pulseGradient(start: PointF, peak: Int) = LinearGradient(
            start.x - dx * PULSE_RADIUS, start.y - dy * PULSE_RADIUS,
            start.x + dx * PULSE_RADIUS, start.y + dy * PULSE_RADIUS,
            intArrayOf(0x00FFFFFF, peak, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
    }

    private data class Axle(val feed: Route, val near: Route, val far: Route, val motor: Path)

    private companion object {
        const val WIDTH = 500
        const val HEIGHT = 378
        const val MAX_WIDTH_DP = 432
        const val FLOW_PERIOD_MS = 1_200L
        const val PULSE_RADIUS = 13f
        const val BATTERY_CELLS = 12
        const val BATTERY_FRONT = 0xFF159607.toInt()
        const val BATTERY_SIDE = 0xFF26B319.toInt()
        const val BATTERY_SEAM = 0x880C7C05.toInt()
        const val DISCHARGE = 0xFF1687DB.toInt()
        const val REGEN = 0xFF19C76A.toInt()
    }
}
