package com.sphy.airconcontroller.ui

import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.R

class DashboardGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private var dashboard = DashboardLayout()
    private var cardViews: Map<DashboardCard, View> = emptyMap()
    private var libraryDialog: AlertDialog? = null
    private var scrollStep = 0
    private var dropTarget: DropTarget? = null
    private var dragX = 0f
    private var dragViewportY = 0f
    private val insertionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_orange)
    }
    private val autoScroll = object : Runnable {
        override fun run() {
            (parent as? ScrollView)?.let { scroll ->
                scroll.scrollBy(0, scrollStep)
                updateDropTarget(dragX, scroll.scrollY + dragViewportY)
            }
            if (scrollStep != 0) postDelayed(this, 16)
        }
    }
    var onCardsChanged: (List<DashboardCard>) -> Unit = {}
    var onEditRequested: () -> Unit = { setEditing(true) }
    var editing: Boolean = false
        private set

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    fun initialize(layout: DashboardLayout, views: Map<DashboardCard, View>) {
        require(views.keys.containsAll(DashboardCard.entries)) { "Every library card needs a view" }
        dashboard = layout
        cardViews = views
        render()
    }

    fun setEditing(value: Boolean) {
        if (editing == value) return
        editing = value
        stopAutoScroll()
        clearDropTarget()
        libraryDialog?.dismiss()
        render()
    }

    private fun changed() {
        onCardsChanged(dashboard.cards)
        render()
    }

    private fun render() {
        cardViews.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        removeAllViews()
        val cards = dashboard.cards
        val slots = cards.size + if (editing) 1 else 0
        repeat((slots + 2) / 3) { rowIndex ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (rowIndex > 0) topMargin = dp(10)
            })
            repeat(3) { column ->
                val index = rowIndex * 3 + column
                val slot = if (index < cards.size) CardSlot() else FrameLayout(context)
                row.addView(slot, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    if (column < 2) rightMargin = dp(10)
                })
                when {
                    index < cards.size -> bindSlot(slot, cards[index])
                    editing && index == cards.size -> {
                        slot.minimumHeight = dp(160)
                        slot.addView(Button(context).apply {
                            text = "+"
                            textSize = 48f
                            contentDescription = context.getString(R.string.dashboard_add_card)
                            setBackgroundResource(R.drawable.bg_info_block)
                            setOnClickListener { showLibrary() }
                        }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    }
                }
            }
        }
    }

    private fun bindSlot(slot: FrameLayout, card: DashboardCard) {
        val content = cardViews.getValue(card)
        slot.tag = card
        slot.setBackgroundResource(R.drawable.bg_info_block)
        content.background = null
        slot.addView(content, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
        ))
        if (!editing) return
        // Cover interactive card contents only while editing so a hold anywhere can start a drag.
        slot.addView(View(context).apply {
            contentDescription = context.getString(R.string.dashboard_move_card, context.getString(card.title))
            isFocusable = true
            setOnLongClickListener {
                val started = slot.startDragAndDrop(
                    ClipData.newPlainText("dashboard-card", card.name),
                    View.DragShadowBuilder(slot), card, 0,
                )
                if (!started) Toast.makeText(context, R.string.dashboard_drag_failed, Toast.LENGTH_SHORT).show()
                started
            }
        }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        slot.addView(Button(context).apply {
            text = "\u00d7"
            textSize = 20f
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.white))
            background = InsetDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(context, R.color.tyre_fault))
            }, dp(10))
            contentDescription = context.getString(R.string.dashboard_remove_card, context.getString(card.title))
            setOnClickListener {
                dashboard.remove(card)
                changed()
            }
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
    }

    override fun onDragEvent(event: DragEvent): Boolean {
        val source = event.localState as? DashboardCard
        if (!editing || source == null || source !in dashboard.cards) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                updateDropTarget(event.x, event.y)
            }
            DragEvent.ACTION_DROP -> {
                val target = findDropTarget(event.x, event.y) ?: return false
                dashboard.move(source, target.index)
                clearDropTarget()
                // Keep the grid attached until Android finishes dispatching this drag.
                post { changed() }
            }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> clearDropTarget()
        }
        return true
    }

    private fun findDropTarget(x: Float, y: Float): DropTarget? {
        val rowIndex = (0 until childCount).minByOrNull { index ->
            val row = getChildAt(index)
            maxOf(row.top - y, y - row.bottom, 0f)
        } ?: return null
        val row = getChildAt(rowIndex) as LinearLayout
        val count = dashboard.cards.size
        for (column in 0 until row.childCount) {
            val slot = row.getChildAt(column)
            val index = rowIndex * 3 + column
            if (index >= count || x < slot.left + slot.width / 2f) {
                val markerX = if (column == 0) slot.left + dp(2f) else slot.left - dp(5f)
                return DropTarget(index.coerceAtMost(count), markerX, row.top.toFloat(), row.bottom.toFloat())
            }
        }
        return DropTarget(
            ((rowIndex + 1) * 3).coerceAtMost(count),
            row.right - dp(2f), row.top.toFloat(), row.bottom.toFloat(),
        )
    }

    private fun updateDropTarget(x: Float, y: Float) {
        dropTarget = findDropTarget(x, y)
        invalidate()
    }

    private fun clearDropTarget() {
        dropTarget = null
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        dropTarget?.let { target ->
            canvas.drawRoundRect(
                target.x - dp(2f), target.top + dp(6f),
                target.x + dp(2f), target.bottom - dp(6f),
                dp(2f), dp(2f), insertionPaint,
            )
        }
    }

    private fun showLibrary() {
        libraryDialog?.dismiss()
        val available = dashboard.available
        val dialog = AlertDialog.Builder(context, R.style.ThemeOverlay_OpenDiKey_DashboardLibrary)
            .setTitle(R.string.dashboard_card_library)
            .apply {
                if (available.isEmpty()) setMessage(R.string.dashboard_all_cards_added)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val previews = available.map { card ->
            FrameLayout(context).apply {
                setBackgroundResource(R.drawable.bg_info_block)
                val content = cardViews.getValue(card)
                content.background = null
                addView(content, FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                ))
                addView(View(context).apply {
                    contentDescription = context.getString(R.string.dashboard_add_named_card, context.getString(card.title))
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        dashboard.add(card)
                        changed()
                    }
                }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        }
        if (previews.isNotEmpty()) {
            val list = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(24), dp(8), dp(24), dp(8))
                previews.forEach { preview ->
                    addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(12)
                    })
                }
            }
            dialog.setView(ScrollView(context).apply { addView(list) })
        }
        dialog.setOnDismissListener {
            previews.forEach { it.removeAllViews() }
            if (libraryDialog === dialog) libraryDialog = null
        }
        libraryDialog = dialog
        dialog.show()
    }

    override fun dispatchDragEvent(event: DragEvent): Boolean {
        if (editing && event.localState is DashboardCard) {
            val scroll = parent as? ScrollView
            if (event.action == DragEvent.ACTION_DRAG_LOCATION && scroll != null) {
                val y = event.y - scroll.scrollY
                dragX = event.x
                dragViewportY = y
                val step = when {
                    y < dp(56) -> -dp(8)
                    y > scroll.height - dp(56) -> dp(8)
                    else -> 0
                }
                if (step != scrollStep) {
                    stopAutoScroll()
                    scrollStep = step
                    if (step != 0) post(autoScroll)
                }
            } else if (event.action == DragEvent.ACTION_DRAG_ENDED ||
                event.action == DragEvent.ACTION_DRAG_EXITED || event.action == DragEvent.ACTION_DROP) {
                stopAutoScroll()
            }
        }
        return super.dispatchDragEvent(event)
    }

    private fun stopAutoScroll() {
        scrollStep = 0
        removeCallbacks(autoScroll)
    }

    override fun onDetachedFromWindow() {
        stopAutoScroll()
        clearDropTarget()
        libraryDialog?.dismiss()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private inner class CardSlot : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val editHold = Runnable {
            if (!editing && isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
                val cancel = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0,
                )
                try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
                onEditRequested()
            }
        }

        init {
            isClickable = true
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (!editing) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        removeCallbacks(editHold)
                        downX = event.x
                        downY = event.y
                        downTime = event.downTime
                        postDelayed(editHold, EDIT_HOLD_MS)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(event.x - downX) > touchSlop ||
                            kotlin.math.abs(event.y - downY) > touchSlop) removeCallbacks(editHold)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
                    MotionEvent.ACTION_POINTER_DOWN -> removeCallbacks(editHold)
                }
            }
            return super.dispatchTouchEvent(event)
        }

        override fun onCancelPendingInputEvents() {
            removeCallbacks(editHold)
            super.onCancelPendingInputEvents()
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(editHold)
            super.onDetachedFromWindow()
        }
    }

    private data class DropTarget(val index: Int, val x: Float, val top: Float, val bottom: Float)

    companion object {
        const val EDIT_HOLD_MS = 1_000L
    }
}
