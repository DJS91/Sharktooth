package com.sphy.airconcontroller.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.sphy.airconcontroller.R
import com.sphy.airconcontroller.dikey.LedMode

class ColorSettingCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val titleView: TextView
    private val swatch: View
    private val controls: View
    private val summary: View
    private val summaryContents: View
    private val summaryTitle: TextView
    private val summaryHex: TextView
    private val summarySaturation: TextView
    private val summaryMode: TextView
    private val summaryBrightness: TextView
    private val summaryRed: TextView
    private val summaryGreen: TextView
    private val summaryBlue: TextView
    private val summarySwatch: View
    private val hueSeek: SeekBar
    private val saturationLabel: TextView
    private val saturationSeek: SeekBar
    private val brightnessLabel: TextView
    private val brightnessSeek: SeekBar
    private val modeLabel: TextView
    private val modeSpinner: Spinner
    private val redLabel: TextView
    private val redSeek: SeekBar
    private val greenLabel: TextView
    private val greenSeek: SeekBar
    private val blueLabel: TextView
    private val blueSeek: SeekBar
    private val hexView: TextView
    private val hsv = floatArrayOf(24f, 1f, 1f)
    private val hueThumb = ColorBarThumb(resources.displayMetrics.density)
    private var suppress = false
    private var currentMode: LedMode = LedMode.ON

    var onColorChanged: ((red: Int, green: Int, blue: Int) -> Unit)? = null
    var onColorCommitted: ((red: Int, green: Int, blue: Int) -> Unit)? = null
    var onModeChanged: ((mode: LedMode) -> Unit)? = null

    init {
        cardElevation = 0f
        radius = 16f * resources.displayMetrics.density
        LayoutInflater.from(context).inflate(R.layout.view_color_setting, this, true)
        titleView = findViewById(R.id.colorSettingTitle)
        swatch = findViewById(R.id.colorSettingSwatch)
        controls = findViewById(R.id.colorSettingControls)
        summary = findViewById(R.id.colorSettingSummaryContainer)
        summaryContents = findViewById(R.id.colorSettingSummaryContents)
        summaryTitle = findViewById(R.id.colorSettingSummaryTitle)
        summaryHex = findViewById(R.id.colorSettingSummaryHex)
        summarySaturation = findViewById(R.id.colorSettingSummarySaturation)
        summaryMode = findViewById(R.id.colorSettingSummaryMode)
        summaryBrightness = findViewById(R.id.colorSettingSummaryBrightness)
        summaryRed = findViewById(R.id.colorSettingSummaryRed)
        summaryGreen = findViewById(R.id.colorSettingSummaryGreen)
        summaryBlue = findViewById(R.id.colorSettingSummaryBlue)
        summarySwatch = findViewById(R.id.colorSettingSummarySwatch)
        hueSeek = findViewById(R.id.colorSettingHue)
        hueSeek.thumbTintList = null
        hueSeek.thumb = hueThumb
        hueSeek.thumbOffset = hueThumb.intrinsicWidth / 2
        saturationLabel = findViewById(R.id.colorSettingSaturationLabel)
        saturationSeek = findViewById(R.id.colorSettingSaturation)
        hueSeek.progressTintList = null
        hueSeek.progressDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            IntArray(13) { Color.HSVToColor(floatArrayOf(it * 30f, 1f, 1f)) },
        ).apply {
            cornerRadius = 6f * resources.displayMetrics.density
            setSize(-1, (12f * resources.displayMetrics.density).toInt())
        }
        brightnessLabel = findViewById(R.id.colorSettingBrightnessLabel)
        brightnessSeek = findViewById(R.id.colorSettingBrightness)
        modeLabel = findViewById(R.id.colorSettingModeLabel)
        modeSpinner = findViewById(R.id.colorSettingMode)
        redLabel = findViewById(R.id.colorSettingRedLabel)
        redSeek = findViewById(R.id.colorSettingRed)
        greenLabel = findViewById(R.id.colorSettingGreenLabel)
        greenSeek = findViewById(R.id.colorSettingGreen)
        blueLabel = findViewById(R.id.colorSettingBlueLabel)
        blueSeek = findViewById(R.id.colorSettingBlue)
        hexView = findViewById(R.id.colorSettingHex)

        modeSpinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            LedMode.entries.map { labelFor(it) }
        )
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppress) return
                val mode = LedMode.entries[position]
                if (mode == currentMode) return
                currentMode = mode
                updateSummary(currentColor())
                onModeChanged?.invoke(mode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        bindSeek(hueSeek) { progress, live ->
            hsv[0] = (progress % 360).toFloat()
            if (hsv[1] == 0f) hsv[1] = 1f
            emit(live = live, source = Source.HUE)
        }
        bindSeek(saturationSeek) { progress, live ->
            hsv[1] = progress / 100f
            emit(live = live, source = Source.SATURATION)
        }
        bindSeek(brightnessSeek) { progress, live ->
            hsv[2] = progress / 255f
            emit(live = live, source = Source.BRIGHTNESS)
        }
        bindSeek(redSeek) { _, live -> onRgbDrag(live) }
        bindSeek(greenSeek) { _, live -> onRgbDrag(live) }
        bindSeek(blueSeek) { _, live -> onRgbDrag(live) }
        syncUi(Source.EXTERNAL)
    }

    fun setTitle(title: CharSequence) {
        titleView.text = title
        summaryTitle.text = title
    }

    fun setExpanded(expanded: Boolean, onExpand: () -> Unit) {
        controls.visibility = if (expanded) View.VISIBLE else View.GONE
        summary.visibility = if (expanded) View.GONE else View.VISIBLE
        summaryContents.setOnClickListener(if (expanded) null else View.OnClickListener { onExpand() })
    }

    fun setModeVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        modeLabel.visibility = v
        modeSpinner.visibility = v
        summaryMode.visibility = v
        updateSummary(currentColor())
    }

    fun setMode(mode: LedMode) {
        currentMode = mode
        suppress = true
        modeSpinner.setSelection(LedMode.entries.indexOf(mode).coerceAtLeast(0), false)
        suppress = false
        updateSummary(currentColor())
    }

    fun mode(): LedMode = currentMode

    fun setRgb(red: Int, green: Int, blue: Int) {
        Color.RGBToHSV(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255), hsv)
        syncUi(Source.EXTERNAL)
    }

    val red: Int get() = Color.red(currentColor())
    val green: Int get() = Color.green(currentColor())
    val blue: Int get() = Color.blue(currentColor())

    private fun labelFor(mode: LedMode): String = when (mode) {
        LedMode.OFF -> context.getString(R.string.led_mode_off)
        LedMode.BLINK -> context.getString(R.string.led_mode_blink)
        LedMode.FLOW -> context.getString(R.string.led_mode_flow)
        LedMode.ON -> context.getString(R.string.led_mode_solid)
        LedMode.BREATH -> context.getString(R.string.led_mode_breath)
    }

    private fun onRgbDrag(live: Boolean) {
        Color.RGBToHSV(redSeek.progress, greenSeek.progress, blueSeek.progress, hsv)
        emit(live = live, source = Source.RGB)
    }

    private fun emit(live: Boolean, source: Source) {
        syncUi(source)
        val color = currentColor()
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        onColorChanged?.invoke(r, g, b)
        if (!live) onColorCommitted?.invoke(r, g, b)
    }

    private fun syncUi(source: Source) {
        suppress = true
        val color = currentColor()
        hueThumb.setColor(color)
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        if (source != Source.HUE) hueSeek.progress = hsv[0].toInt()
        if (source != Source.SATURATION) saturationSeek.progress = (hsv[1] * 100f).toInt()
        if (source != Source.BRIGHTNESS) {
            brightnessSeek.progress = (hsv[2] * 255f).toInt().coerceIn(0, 255)
        }
        if (source != Source.RGB) {
            redSeek.progress = r
            greenSeek.progress = g
            blueSeek.progress = b
        }
        brightnessLabel.text = context.getString(R.string.color_brightness_fmt, brightnessSeek.progress)
        saturationLabel.text = context.getString(R.string.color_saturation_fmt, saturationSeek.progress)
        redLabel.text = context.getString(R.string.color_red_fmt, r)
        greenLabel.text = context.getString(R.string.color_green_fmt, g)
        blueLabel.text = context.getString(R.string.color_blue_fmt, b)
        hexView.text = "#%02X%02X%02X".format(r, g, b)
        val swatchBg = (swatch.background?.mutate() as? GradientDrawable)
            ?: GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3f * resources.displayMetrics.density
                swatch.background = this
            }
        swatchBg.setColor(color)
        updateSummary(color)
        suppress = false
    }

    private fun updateSummary(color: Int) {
        (summarySwatch.background.mutate() as GradientDrawable).setColor(color)
        summaryHex.text = "#%02X%02X%02X".format(Color.red(color), Color.green(color), Color.blue(color))
        summarySaturation.text = context.getString(R.string.color_summary_sat, saturationSeek.progress)
        summaryMode.text = context.getString(R.string.color_summary_mode, labelFor(currentMode))
        summaryBrightness.text = context.getString(R.string.color_summary_brightness, brightnessSeek.progress)
        summaryRed.text = context.getString(R.string.color_summary_red, Color.red(color))
        summaryGreen.text = context.getString(R.string.color_summary_green, Color.green(color))
        summaryBlue.text = context.getString(R.string.color_summary_blue, Color.blue(color))
    }

    private fun currentColor(): Int = Color.HSVToColor(hsv)

    private fun bindSeek(seek: SeekBar, onUser: (progress: Int, live: Boolean) -> Unit) {
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !suppress) onUser(progress, true)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!suppress) onUser(seek.progress, false)
            }
        })
    }

    private enum class Source { HUE, SATURATION, BRIGHTNESS, RGB, EXTERNAL }
}
