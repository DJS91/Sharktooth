package com.sphy.airconcontroller.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

class DashboardSwipeHost(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {
    var pagingEnabled = true
        set(value) {
            field = value
            if (!value) resetSwipe()
        }
    var canSwipe: (Int) -> Boolean = { false }
    var onPreview: (Int) -> Unit = {}
    var onSwipeFinished: (Int) -> Unit = {}

    private val page: View get() = getChildAt(0)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var horizontal = false
    private var outgoing: Bitmap? = null
    private var offset = 0f
    private var direction = 0
    private var previewDirection = 0
    // Settling is the remainder of a scroll gesture, not a window transition.
    private val scroller = OverScroller(context, DecelerateInterpolator())
    private var settlingResult: Int? = null
    private val settleFrame = object : Runnable {
        override fun run() {
            val result = settlingResult ?: return
            scroller.computeScrollOffset()
            positionPages(scroller.currX.toFloat())
            if (scroller.isFinished) {
                settlingResult = null
                finishSwipe(result)
            } else {
                postOnAnimation(this)
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (settlingResult != null) return true
        val handled = super.dispatchTouchEvent(event)
        // The intercepting MOVE cancels the child stream but is not sent to onTouchEvent.
        if (horizontal && outgoing == null && event.actionMasked == MotionEvent.ACTION_MOVE) {
            updateDrag(event.x - startX)
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        track(event)
        return horizontal
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pagingEnabled) return false
        track(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (horizontal) updateDrag(event.x - startX)
            MotionEvent.ACTION_UP -> {
                val distance = event.x - startX
                val minimumDistance = maxOf(slop * 3f, minOf(width * 0.1f, slop * 10f))
                if (horizontal) {
                    // UP can be far past the last MOVE. Use it to decide, not to reposition the pages.
                    settle(abs(distance) >= minimumDistance && distance * direction < 0f)
                }
                horizontal = false
                tracking = false
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> if (outgoing != null) {
                settle(complete = false)
            }
        }
        return true
    }

    private fun updateDrag(distance: Float) {
        if (width == 0 || height == 0) return
        if (outgoing == null) {
            // Both dashboards can contain the same live card views. Freeze the outgoing
            // viewport before reusing those views in the incoming dashboard.
            outgoing = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.density = resources.displayMetrics.densityDpi
                page.draw(Canvas(it))
            }
        }
        val target = if (distance < 0f) 1 else -1
        direction = if (distance != 0f && canSwipe(target)) target else 0
        if (direction != 0 && direction != previewDirection) {
            previewDirection = direction
            onPreview(direction)
        }
        val limit = width.toFloat()
        positionPages(if (direction == 0) (distance * 0.2f).coerceIn(-limit / 5, limit / 5)
            else distance.coerceIn(-limit, limit))
    }

    private fun positionPages(position: Float) {
        offset = position
        page.translationX = if (direction == 0) width.toFloat() else position + direction * width
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipRect(0, 0, width, height)
        super.dispatchDraw(canvas)
        outgoing?.let { canvas.drawBitmap(it, offset, 0f, null) }
        canvas.restoreToCount(save)
    }

    private fun settle(complete: Boolean) {
        if (outgoing == null) return
        val result = if (complete) direction else 0
        val start = offset.roundToInt()
        val distance = -result * width - start
        if (distance == 0) {
            finishSwipe(result)
            return
        }
        val remaining = abs(distance).toFloat() / width.coerceAtLeast(1)
        val durationMs = if (complete) 240 + 160 * remaining else 100 + 140 * remaining
        settlingResult = result
        scroller.startScroll(start, 0, distance, 0, durationMs.roundToInt())
        postOnAnimation(settleFrame)
    }

    fun resetSwipe() {
        removeCallbacks(settleFrame)
        scroller.forceFinished(true)
        settlingResult = null
        horizontal = false
        tracking = false
        if (outgoing != null) finishSwipe(0)
    }

    private fun finishSwipe(result: Int) {
        outgoing = null
        direction = 0
        previewDirection = 0
        offset = 0f
        page.translationX = 0f
        onSwipeFinished(result)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        resetSwipe()
        super.onDetachedFromWindow()
    }

    private fun track(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                tracking = pagingEnabled
                horizontal = false
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = abs(event.x - startX)
                val dy = abs(event.y - startY)
                if (maxOf(dx, dy) > slop) {
                    horizontal = pagingEnabled && dx > dy
                    tracking = false
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                horizontal = false
            }
        }
    }
}
