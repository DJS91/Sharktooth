package com.sphy.airconcontroller.ui

import android.content.Context
import android.widget.FrameLayout
import kotlin.math.min

/** Measures without wrapping, then scales the complete live card into its allotted space. */
internal class FittedCardPreview(context: Context) : FrameLayout(context) {
    init {
        val inset = (8 * resources.displayMetrics.density).toInt()
        setPadding(inset, inset, inset, inset)
        clipChildren = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val card = getChildAt(0)
        if (card == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val horizontalPadding = paddingLeft + paddingRight
        val verticalPadding = paddingTop + paddingBottom
        val unconstrained = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        card.measure(unconstrained, unconstrained)
        val width = maxOf(card.measuredWidth, MeasureSpec.getSize(widthMeasureSpec) - horizontalPadding)
        card.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), unconstrained)
        setMeasuredDimension(
            resolveSize(card.measuredWidth + horizontalPadding, widthMeasureSpec),
            resolveSize(card.measuredHeight + verticalPadding, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val card = getChildAt(0) ?: return
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        if (card.width == 0 || card.height == 0) return
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        val scale = min(availableWidth.toFloat() / card.width, availableHeight.toFloat() / card.height)
        card.scaleX = scale
        card.scaleY = scale
        card.translationX = paddingLeft + (availableWidth - card.width * scale) / 2f - card.pivotX * (1 - scale)
        card.translationY = paddingTop + (availableHeight - card.height * scale) / 2f - card.pivotY * (1 - scale)
    }
}
