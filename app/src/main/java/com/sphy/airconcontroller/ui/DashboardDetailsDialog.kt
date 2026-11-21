package com.sphy.airconcontroller.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.sphy.airconcontroller.R
import com.sphy.airconcontroller.storage.SidebarSide

/** Temporarily expands the bound card, so live updates and animations keep the same source. */
internal class DashboardDetailsDialog(
    context: Context,
    card: View,
    graphs: View,
    controls: View?,
    side: SidebarSide,
    fitPreview: Boolean = false,
    onDismiss: () -> Unit,
) {
    val dialog: AlertDialog

    init {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val parent = card.parent as ViewGroup
        val index = parent.indexOfChild(card)
        val params = card.layoutParams
        val background = card.background
        val clickable = card.isClickable
        val scaleX = card.scaleX
        val scaleY = card.scaleY
        val translationX = card.translationX
        val translationY = card.translationY
        val gauge = card.findViewById<PedalGauge>(R.id.dashboardPedalGauge)
        val gaugeHeight = gauge?.maxHeight
        gauge?.maxHeight = Int.MAX_VALUE
        val textSizes = ArrayList<Pair<TextView, Float>>()
        fun enlarge(view: View) {
            if (view is TextView) {
                textSizes += view to view.textSize
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * 1.25f)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) enlarge(view.getChildAt(i))
        }
        enlarge(card)
        parent.removeView(card)
        val placeholder = View(context)
        parent.addView(placeholder, index, params)
        card.isClickable = false
        card.setBackgroundResource(R.drawable.bg_dashboard_card)
        val cardHost = (if (fitPreview) FittedCardPreview(context) else FrameLayout(context)).apply {
            id = R.id.dashboardDetailsCard
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val details = LinearLayout(context).apply {
            id = R.id.dashboardDetailsControls
            orientation = LinearLayout.VERTICAL
            val preview = if (fitPreview) cardHost else ScrollView(context).apply { addView(cardHost) }
            addView(preview,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            controls?.let { addView(it) }
        }
        val row = LinearLayout(context).apply {
            id = R.id.dashboardDetailsColumns
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f))
            graphs.id = R.id.dashboardDetailsGraphs
            addView(graphs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        DetailsColumns.arrange(row, details, graphs, side)
        dialog = AlertDialog.Builder(context).setView(row).create()
        dialog.setCanceledOnTouchOutside(true)
        details.addView(Button(context).apply {
            id = R.id.dashboardDetailsClose
            setText(R.string.dashboard_details_close)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setOnDismissListener {
            cardHost.removeView(card)
            parent.removeView(placeholder)
            card.background = background
            card.isClickable = clickable
            card.scaleX = scaleX
            card.scaleY = scaleY
            card.translationX = translationX
            card.translationY = translationY
            textSizes.forEach { (text, size) -> text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size) }
            if (gauge != null && gaugeHeight != null) gauge.maxHeight = gaugeHeight
            parent.addView(card, index.coerceAtMost(parent.childCount), params)
            onDismiss()
        }
        dialog.show()
        val metrics = context.resources.displayMetrics
        dialog.window?.setLayout((metrics.widthPixels * 0.94f).toInt(), (metrics.heightPixels * 0.92f).toInt())
    }
}
