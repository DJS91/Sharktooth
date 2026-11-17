package com.sphy.airconcontroller

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sphy.airconcontroller.dikey.DialDisplay
import com.sphy.airconcontroller.dikey.DialDisplayType
import com.sphy.airconcontroller.dikey.DialDisplays
import com.sphy.airconcontroller.dikey.DiKeySession
import com.sphy.airconcontroller.dikey.LedMode
import com.sphy.airconcontroller.lighting.LightingPeriod
import com.sphy.airconcontroller.lighting.LightingScheduler
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.ColorSettingCard
import com.sphy.airconcontroller.ui.DiKeyBarPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal fun previewDialDisplays(displays: DialDisplays, isConnected: Boolean, isDebug: Boolean): DialDisplays =
    if (isDebug && !isConnected) {
        DialDisplays(
            DialDisplay(DialDisplayType.PASSENGER_TEMP.code, 24),
            DialDisplay(DialDisplayType.DRIVER_FAN.code, 3),
        )
    } else {
        displays
    }

class ColorConfigActivity : HomeActivity() {
    private lateinit var session: DiKeySession
    private lateinit var settings: AppSettings
    private lateinit var debugDialLabel: android.widget.TextView
    private lateinit var periodToggle: MaterialButtonToggleGroup
    private lateinit var syncGroup: MaterialButtonToggleGroup

    private lateinit var colorLeft: ColorSettingCard
    private lateinit var colorMiddle: ColorSettingCard
    private lateinit var colorRight: ColorSettingCard
    private lateinit var colorKeys: ColorSettingCard
    private lateinit var barPreview: DiKeyBarPreview

    private val mainHandler = Handler(Looper.getMainLooper())
    private val applyBarRunnables = Array<Runnable?>(4) { null }
    private val applyDispatchers = Array(4) { index ->
        Runnable {
            val task = applyBarRunnables[index]
            applyBarRunnables[index] = null
            task?.run()
        }
    }
    private var suppressUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_color_config)

        session = OpenDiKeyApp.from(this).dikey
        settings = session.settings
        debugDialLabel = findViewById(R.id.colorDebugDialLabel)
        periodToggle = findViewById(R.id.colorPeriodToggle)
        syncGroup = findViewById(R.id.colorSyncGroup)

        findViewById<android.widget.ImageButton>(R.id.colorBackButton).setOnClickListener {
            finish()
        }

        colorLeft = findViewById(R.id.colorLeft)
        colorMiddle = findViewById(R.id.colorMiddle)
        colorRight = findViewById(R.id.colorRight)
        colorKeys = findViewById(R.id.colorKeys)
        barPreview = findViewById(R.id.colorBarPreview)

        colorLeft.setTitle(getString(R.string.color_left))
        colorMiddle.setTitle(getString(R.string.color_middle))
        colorRight.setTitle(getString(R.string.color_right))
        colorKeys.setTitle(getString(R.string.color_keys))
        colorKeys.setModeVisible(false)

        setupSyncControl()
        selectCard(savedInstanceState?.getInt(STATE_SELECTED_CARD, 0) ?: 0)
        setupPeriodControls()
        reloadCardsFromEditingProfile()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(session.connectionState, session.controller.dialDisplays) { state, displays -> state to displays }
                    .collect { (state, displays) ->
                        val isDebug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                        val isConnected = state == DiKeySession.ConnectionState.CONNECTED
                        debugDialLabel.visibility = if (isDebug && !isConnected) View.VISIBLE else View.GONE
                        barPreview.setDialDisplays(previewDialDisplays(displays, isConnected, isDebug))
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LightingScheduler.preview(this, settings.editingLightingPeriod)
    }

    override fun onPause() {
        flushScheduled()
        LightingScheduler.endPreview(this)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_CARD, selectedCard)
        super.onSaveInstanceState(outState)
    }

    private var selectedCard = 0
    private fun selectCard(selected: Int, animate: Boolean = false) {
        val cards = listOf(colorLeft, colorMiddle, colorRight, colorKeys)
        val container = findViewById<LinearLayout>(R.id.colorCards)
        if (animate && container.isLaidOut) {
            val transition = ChangeBounds().setDuration(350)
            transition.interpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)
            TransitionManager.beginDelayedTransition(container, transition)
        }
        selectedCard = selected.coerceIn(cards.indices).let {
            if (settings.barsSynced && it in 1..2) 0 else it
        }
        cards.forEachIndexed { index, card ->
            card.visibility = if (settings.barsSynced && index in 1..2) View.GONE else View.VISIBLE
            val expanded = index == selectedCard
            val params = card.layoutParams as LinearLayout.LayoutParams
            params.width = if (expanded) 0 else (104 * resources.displayMetrics.density).toInt()
            params.weight = if (expanded) 1f else 0f
            card.layoutParams = params
            card.setExpanded(expanded) { selectCard(index, animate = true) }
        }
    }

    private fun setupSyncControl() {
        if (settings.barsSynced) syncGroup.check(R.id.colorSyncBars)
        syncGroup.addOnButtonCheckedListener { _, id, checked ->
            if (id != R.id.colorSyncBars) return@addOnButtonCheckedListener
            flushScheduled()
            settings.setBarsSynced(checked)
            session.applyBarLightingPeriod(settings.liveLightingPeriod())
            selectCard(selectedCard)
            reloadCardsFromEditingProfile()
        }
    }

    private fun setupPeriodControls() {
        suppressUi = true
        periodToggle.check(
            if (settings.editingLightingPeriod == LightingPeriod.NIGHT) {
                R.id.colorPeriodNight
            } else {
                R.id.colorPeriodDay
            }
        )
        suppressUi = false

        periodToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressUi) return@addOnButtonCheckedListener
            flushScheduled()
            settings.editingLightingPeriod =
                if (checkedId == R.id.colorPeriodNight) LightingPeriod.NIGHT else LightingPeriod.DAY
            LightingScheduler.preview(this, settings.editingLightingPeriod)
            reloadCardsFromEditingProfile()
        }
    }

    private fun reloadCardsFromEditingProfile() {
        bindBar(colorLeft, 1)
        bindBar(colorMiddle, 2)
        bindBar(colorRight, 3)
        bindBacklight(colorKeys)
        colorLeft.setTitle(getString(if (settings.barsSynced) R.string.color_all_bars else R.string.color_left))
        if (settings.barsSynced) {
            colorLeft.setModeVisible(false)
            colorLeft.onModeChanged = null
            colorLeft.onColorChanged = { r, g, b ->
                for (bar in 1..3) {
                    barPreview.setBarColor(bar, r, g, b)
                    scheduleApply(bar, r, g, b, settings.modeForBar(bar))
                }
            }
            colorLeft.onColorCommitted = { r, g, b ->
                for (bar in 1..3) {
                    cancelSchedule(bar)
                    session.applyBarColor(bar, r, g, b, settings.modeForBar(bar))
                }
            }
        }
    }

    private fun bindBar(card: ColorSettingCard, bar: Int) {
        val color = settings.colorForBar(bar)
        val mode = LedMode.entries.find { it.code == settings.modeForBar(bar) } ?: LedMode.ON
        card.onModeChanged = null
        card.setModeVisible(true)
        card.setMode(mode)
        card.setRgb(color.red, color.green, color.blue)
        barPreview.setBarColor(bar, card.red, card.green, card.blue)
        card.onModeChanged = { next ->
            session.applyBarColor(bar, card.red, card.green, card.blue, next.code)
        }
        card.onColorChanged = { r, g, b ->
            barPreview.setBarColor(bar, r, g, b)
            scheduleApply(bar, r, g, b, card.mode().code)
        }
        card.onColorCommitted = { r, g, b ->
            cancelSchedule(bar)
            session.applyBarColor(bar, r, g, b, card.mode().code)
        }
    }

    private fun bindBacklight(card: ColorSettingCard) {
        val color = settings.colorForBacklight()
        card.setRgb(color.red, color.green, color.blue)
        barPreview.setKeyBacklightColor(card.red, card.green, card.blue)
        card.onColorChanged = { r, g, b ->
            barPreview.setKeyBacklightColor(r, g, b)
            scheduleApply(BACKLIGHT_SLOT, r, g, b, null)
        }
        card.onColorCommitted = { r, g, b ->
            cancelSchedule(BACKLIGHT_SLOT)
            session.applyBacklight(r, g, b)
        }
    }

    private fun scheduleApply(slot: Int, r: Int, g: Int, b: Int, mode: Int?) {
        val index = slotIndex(slot)
        val scheduled = applyBarRunnables[index] != null
        val task = Runnable {
            if (slot == BACKLIGHT_SLOT) {
                session.applyBacklight(r, g, b)
            } else {
                session.applyBarColor(slot, r, g, b, mode)
            }
        }
        applyBarRunnables[index] = task
        if (!scheduled) mainHandler.postDelayed(applyDispatchers[index], APPLY_INTERVAL_MS)
    }

    private fun cancelSchedule(slot: Int) {
        val index = slotIndex(slot)
        mainHandler.removeCallbacks(applyDispatchers[index])
        applyBarRunnables[index] = null
    }

    private fun flushScheduled() {
        for (slot in 0..3) {
            val task = applyBarRunnables[slot] ?: continue
            mainHandler.removeCallbacks(applyDispatchers[slot])
            applyBarRunnables[slot] = null
            task.run()
        }
    }

    private fun slotIndex(slot: Int) = if (slot == BACKLIGHT_SLOT) 3 else slot - 1

    companion object {
        private const val APPLY_INTERVAL_MS = 80L
        private const val BACKLIGHT_SLOT = 0
        private const val STATE_SELECTED_CARD = "selected_color_card"
    }
}
