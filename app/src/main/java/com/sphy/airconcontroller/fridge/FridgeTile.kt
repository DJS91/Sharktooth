package com.sphy.airconcontroller.fridge

import android.content.pm.ApplicationInfo
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.sphy.airconcontroller.HomeActivity
import com.sphy.airconcontroller.OpenDiKeyApp
import com.sphy.airconcontroller.R

/**
 * Home tile. Hidden until the fridge first delivers live readings in this app run; after that
 * it stays, showing temperatures and Eco/Max, or "Disconnected" with a Reconnect button.
 * The dashboard variant stays visible when disconnected and opens the same controls in a popup.
 * Debug builds preview only the sidebar tile until a real fridge is seen.
 */
class FridgeTile(
    private val activity: HomeActivity,
    private val tile: View = activity.findViewById(R.id.homeFridgeButton),
    private val dashboard: Boolean = false,
) {
    private val temperatures = tile.findViewById<TextView>(R.id.homeFridgeTemperatures)
    private val status = tile.findViewById<TextView>(R.id.homeFridgeStatus)
    private val disconnected = tile.findViewById<TextView>(R.id.homeFridgeDisconnected)
    private val reconnect = tile.findViewById<Button>(R.id.homeFridgeReconnect)
    private val controller = OpenDiKeyApp.from(activity).fridge
    private val listener: () -> Unit = { render() }
    private var active = false

    init {
        tile.setOnClickListener {
            if (dashboard) {
                if (activity.supportFragmentManager.findFragmentByTag(CONTROLS_TAG) == null) {
                    FridgeControlsFragment().showNow(activity.supportFragmentManager, CONTROLS_TAG)
                }
            } else activity.navigateTo(FridgeActivity::class.java)
        }
        reconnect.setOnClickListener { controller.reconnect() }
        render()
    }

    fun start() {
        if (active) return
        active = true
        controller.addListener(listener)
        controller.attach()
        render()
    }

    fun stop() {
        if (!active) return
        active = false
        controller.removeListener(listener)
        // The ESP32 link stays up app-wide (history keeps recording); only the
        // Android BLE backup stops once no fridge UI is visible.
        controller.detach()
    }

    private fun render() {
        val actual = controller.status
        val preview = !dashboard && isFridgeTilePreview(
            activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            controller.seenConnected,
            actual,
        )
        tile.visibility = if (dashboard || controller.seenConnected || actual != null || preview) View.VISIBLE else View.GONE
        val value = if (preview) PREVIEW_STATUS else actual
        if (value != null) {
            temperatures.visibility = View.VISIBLE
            disconnected.visibility = View.GONE
            temperatures.text = temperatureText(value)
            val max = controller.displayMax ?: value.max
            val mode = activity.getString(
                when {
                    (controller.displayOn ?: value.on) == false -> R.string.fridge_off
                    max -> R.string.fridge_mode_max
                    else -> R.string.fridge_mode_eco
                },
            )
            status.text = activity.getString(R.string.fridge_tile_voltage, value.voltage) + "\n" + mode
            reconnect.visibility = View.GONE
            status.visibility = View.VISIBLE
        } else {
            temperatures.visibility = View.GONE
            disconnected.visibility = View.VISIBLE
            status.text = controller.message
            // The button takes the status line's place so the tile keeps the row's height;
            // the detailed reason remains on the fridge page and in the accessibility text.
            status.visibility = View.GONE
            reconnect.visibility = View.VISIBLE
        }
        tile.contentDescription = listOfNotNull(
            activity.getString(R.string.fridge_title),
            if (preview) activity.getString(R.string.fridge_tile_example_readings) else null,
            if (value != null) temperatures.text else disconnected.text,
            status.text,
        ).joinToString(", ")
    }

    /** Small grey side letters keep the two temperatures prominent. */
    private fun temperatureText(value: EvakoolProtocol.Status): CharSequence {
        val unit = if (value.celsius) "C" else "F"
        val muted = activity.getColor(R.color.text_muted)
        val text = SpannableStringBuilder()
        fun label(letter: String) {
            val start = text.length
            text.append(letter)
            text.setSpan(RelativeSizeSpan(0.6f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(muted), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(' ')
        }
        label("L")
        text.append("${value.left}°$unit")
        text.append('\n')
        label("R")
        text.append("${value.right}°$unit")
        return text
    }

    companion object {
        private const val CONTROLS_TAG = "dashboard_fridge_controls"
        private val PREVIEW_STATUS = EvakoolProtocol.Status(
            on = true, celsius = true, left = 4, right = -18,
            leftTarget = 4, rightTarget = -18, voltage = "13.8",
        )
    }
}

internal fun isFridgeTilePreview(
    isDebug: Boolean,
    seenConnected: Boolean,
    status: EvakoolProtocol.Status?,
): Boolean = isDebug && !seenConnected && status == null
