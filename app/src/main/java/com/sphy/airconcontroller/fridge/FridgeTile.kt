package com.sphy.airconcontroller.fridge

import android.content.Context
import android.os.Handler
import android.os.Looper
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

class FridgeTile(
    private val activity: HomeActivity,
    private val tile: View,
    private val sample: EvakoolProtocol.Status? = null,
) {
    private val temperatures = tile.findViewById<TextView>(R.id.homeFridgeTemperatures)
    private val status = tile.findViewById<TextView>(R.id.homeFridgeStatus)
    private val power = tile.findViewById<TextView>(R.id.homeFridgePower)
    private val updated = tile.findViewById<TextView>(R.id.homeFridgeUpdated)
    private val disconnected = tile.findViewById<TextView>(R.id.homeFridgeDisconnected)
    private val reconnect = tile.findViewById<Button>(R.id.homeFridgeReconnect)
    private val controller = OpenDiKeyApp.from(activity).fridge
    private val listener: () -> Unit = { render() }
    private var active = false
    private val sampleTime = sample?.let { System.currentTimeMillis() }
    private val handler = Handler(Looper.getMainLooper())
    private val refreshAge = Runnable { updateAge() }

    init {
        if (sample == null) tile.setOnClickListener {
            if (activity.supportFragmentManager.findFragmentByTag(CONTROLS_TAG) == null) {
                FridgeControlsFragment().showNow(activity.supportFragmentManager, CONTROLS_TAG)
            }
        }
        reconnect.setOnClickListener { controller.reconnect() }
        render()
    }

    fun start() {
        if (active) return
        active = true
        if (sample == null) {
            controller.addListener(listener)
            controller.attach()
        }
        render()
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(refreshAge)
        if (sample != null) return
        controller.removeListener(listener)
        // The ESP32 link stays up app-wide (history keeps recording); only the
        // Android BLE backup stops once no fridge UI is visible.
        controller.detach()
    }

    private fun render() {
        val value = sample ?: controller.status
        if (value != null) {
            temperatures.visibility = View.VISIBLE
            disconnected.visibility = View.GONE
            temperatures.text = temperatureText(value)
            val max = if (sample != null) value.max else controller.displayMax ?: value.max
            val on = if (sample != null) value.on else controller.displayOn ?: value.on
            status.text = activity.getString(
                R.string.fridge_tile_details,
                activity.getString(R.string.fridge_tile_voltage, value.voltage),
                activity.getString(if (max) R.string.fridge_mode_max else R.string.fridge_mode_eco),
            )
            power.text = activity.getString(
                R.string.fridge_tile_power, activity.getString(if (on) R.string.fridge_on else R.string.fridge_off),
            )
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
        power.visibility = if (value != null) View.VISIBLE else View.GONE
        updateAge()
    }

    private fun updateAge() {
        handler.removeCallbacks(refreshAge)
        val timestamp = sampleTime ?: controller.statusUpdatedAtMillis
        updated.visibility = if (timestamp != null) View.VISIBLE else View.GONE
        updated.text = timestamp?.let {
            val ageMillis = (System.currentTimeMillis() - it).coerceAtLeast(0)
            val unitMillis = if (ageMillis < 60_000) 1_000 else 60_000
            if (active) handler.postDelayed(refreshAge, unitMillis - ageMillis % unitMillis)
            activity.getString(
                R.string.fridge_tile_updated,
                formatFridgeUpdateAge(activity, ageMillis),
            )
        }
        tile.contentDescription = listOfNotNull(
            activity.getString(R.string.fridge_title),
            if (temperatures.visibility == View.VISIBLE) temperatures.text else disconnected.text,
            status.text,
            power.text.takeIf { power.visibility == View.VISIBLE },
            updated.text.takeIf { timestamp != null },
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
        text.append("  ")
        label("R")
        text.append("${value.right}°$unit")
        return text
    }

    companion object {
        private const val CONTROLS_TAG = "dashboard_fridge_controls"
    }
}

internal fun formatFridgeUpdateAge(context: Context, ageMillis: Long): String {
    val seconds = ageMillis / 1_000
    val inSeconds = seconds < 60
    val quantity = (if (inSeconds) seconds else seconds / 60).toInt()
    val resource = if (inSeconds) R.plurals.fridge_tile_seconds_ago else R.plurals.fridge_tile_minutes_ago
    return context.resources.getQuantityString(resource, quantity, quantity)
}
