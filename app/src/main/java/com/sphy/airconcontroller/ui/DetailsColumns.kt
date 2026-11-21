package com.sphy.airconcontroller.ui

import android.view.View
import android.widget.LinearLayout
import com.sphy.airconcontroller.storage.SidebarSide

internal object DetailsColumns {
    fun arrange(row: LinearLayout, controls: View, graphs: View, side: SidebarSide) {
        val controlsFirst = (side == SidebarSide.LEFT) != (row.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        row.removeView(controls)
        row.removeView(graphs)
        val ordered = if (controlsFirst) listOf(controls, graphs) else listOf(graphs, controls)
        ordered.forEachIndexed { index, view ->
            val params = view.layoutParams as LinearLayout.LayoutParams
            params.marginStart = if (index == 0) 0 else (12 * row.resources.displayMetrics.density).toInt()
            params.marginEnd = 0
            row.addView(view, params)
        }
    }
}
