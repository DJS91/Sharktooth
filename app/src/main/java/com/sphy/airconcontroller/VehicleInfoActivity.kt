package com.sphy.airconcontroller

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.text.Annotation
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sphy.airconcontroller.byd.BydTyreController
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.byd.TabletSocReader
import com.sphy.airconcontroller.fridge.FridgeTile
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.AttitudeHorizonView
import com.sphy.airconcontroller.ui.DashboardCard
import com.sphy.airconcontroller.ui.DashboardGrid
import com.sphy.airconcontroller.ui.DashboardLayout
import com.sphy.airconcontroller.ui.DashboardSamples
import com.sphy.airconcontroller.ui.DashboardSwipeHost
import com.sphy.airconcontroller.ui.PercentBar
import com.sphy.airconcontroller.ui.PowerFlowView
import com.sphy.airconcontroller.ui.RegenEnergySampler
import com.sphy.airconcontroller.ui.PedalGauge
import com.sphy.airconcontroller.ui.EngineRpmGraph
import com.sphy.airconcontroller.ui.EngineRpmHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.text.NumberFormat
import kotlin.math.atan2
import kotlin.math.sqrt

/** Live vehicle cards in a saved, editable three-column dashboard. */
open class VehicleInfoActivity : HomeActivity() {
    private lateinit var info: BydVehicleInfoController
    private lateinit var tabletSoc: TabletSocReader
    private lateinit var settings: AppSettings
    private lateinit var dashboardGrid: DashboardGrid
    private val showDashboardSamples by lazy {
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            !intent.getBooleanExtra(EXTRA_LIVE_DASHBOARD, false)
    }
    private lateinit var swipeHost: DashboardSwipeHost
    private val pageDots = mutableListOf<View>()
    private var dashboardPage = 0
    private var previewPage: Int? = null
    private var outgoingScrollY = 0
    private var pageRenderGeneration = 0
    private lateinit var fridgeCard: FridgeTile
    private val editBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setDashboardEditing(false)
    }
    private var dashboardReady = false
    private var sensorManager: SensorManager? = null

    private lateinit var socBar: PercentRow
    private lateinit var batteryHealthValue: TextView
    private lateinit var tyresHero: TextView
    private lateinit var fuelBar: PercentRow
    private lateinit var batterySubtitle: TextView
    private lateinit var batteryMetaHost: LinearLayout

    private lateinit var pedalGauge: PedalGauge
    private lateinit var rpmGraph: EngineRpmGraph
    private lateinit var driveHero: TextView
    private lateinit var driveSubtitle: TextView
    private lateinit var parkBrakeValue: TextView
    private lateinit var powerFlowView: PowerFlowView
    private lateinit var powerFlowRpm: TextView
    private lateinit var powerFlowPower: TextView
    private lateinit var powerFlowRegen: TextView
    private val regenSampler = RegenEnergySampler()
    private val regenFormat = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 3
        maximumFractionDigits = 3
    }
    private lateinit var pedalPeakText: TextView
    private var displayedPedalPeaks: AppSettings.PedalPeaks? = null
    private lateinit var engineHost: LinearLayout
    private lateinit var chassisHost: LinearLayout
    private lateinit var mileageHost: LinearLayout
    private lateinit var consumptionHost: LinearLayout

    private lateinit var tyreSlots: List<TyreSlot>
    private lateinit var tyreUnboundHint: TextView
    private lateinit var attitudePitchView: AttitudeHorizonView
    private lateinit var attitudeRollView: AttitudeHorizonView
    private lateinit var tabletMetaHost: LinearLayout

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!dashboardReady || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val ax = event.values[0].toDouble()
            val ay = event.values[1].toDouble()
            val az = event.values[2].toDouble()
            bindDashboardAttitude(ax, ay, az)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle_info)

        info = BydVehicleInfoController(this)
        tabletSoc = TabletSocReader(this)
        settings = AppSettings(this)
        dashboardGrid = findViewById(R.id.dashboardGrid)
        swipeHost = findViewById(R.id.dashboardSwipeHost)
        dashboardPage = (savedInstanceState?.getInt(STATE_PAGE) ?: 0).coerceIn(0, PAGE_COUNT - 1)

        buildDashboard()
        dashboardGrid.onCardsChanged = { settings.saveDashboardCardsForPage(dashboardPage, it) }
        dashboardGrid.onEditRequested = ::enterDashboardEditMode
        swipeHost.canSwipe = { direction -> dashboardPage + direction in 0 until PAGE_COUNT }
        swipeHost.onPreview = ::previewDashboardPage
        swipeHost.onSwipeFinished = ::finishDashboardSwipe
        buildPageDots()
        findViewById<View>(R.id.dashboardEditEmpty).setOnClickListener { enterDashboardEditMode() }
        onBackPressedDispatcher.addCallback(this, editBackCallback)
        findViewById<View>(R.id.dashboardDone).setOnClickListener { setDashboardEditing(false) }
        setDashboardEditing(savedInstanceState?.getBoolean(STATE_EDITING) == true ||
            intent.getBooleanExtra(EXTRA_EDIT_DASHBOARD, false))
        intent.removeExtra(EXTRA_EDIT_DASHBOARD)

        if (showDashboardSamples) {
            findViewById<TextView>(R.id.homeTitle).append(" · " + getString(R.string.dashboard_sample_data))
            bindSnapshot(DashboardSamples.vehicle)
            bindTabletSoc(DashboardSamples.headUnit)
            bindDashboardAttitude(ax = 1.2, ay = -0.8, az = 9.7)
            return
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val snap = withContext(Dispatchers.IO) { info.snapshot(includeImu = false) }
                    val capturedAt = SystemClock.elapsedRealtime()
                    val captureDay = LocalDate.now().toEpochDay()
                    val recovered = regenSampler.sample(captureDay, capturedAt, snap.dynamics.enginePowerKw)
                    showDailyRegen(settings.addDailyRegenKwh(captureDay, recovered))
                    val soc = withContext(Dispatchers.IO) { tabletSoc.snapshot() }
                    bindSnapshot(snap)
                    bindTabletSoc(soc)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val drive = withContext(Dispatchers.IO) { info.readDriveLive() }
                    if (dashboardReady) {
                        driveHero.visibility = View.VISIBLE
                        driveHero.text = fmt(drive.speedKmh, " km/h")
                        pedalGauge.set(drive.accelPedalPct, drive.brakePedalPct)
                        bindPedalPeaks(settings.recordPedalPeaks(
                            LocalDate.now().toEpochDay(), drive.accelPedalPct, drive.brakePedalPct,
                        ))
                    }
                    delay(DRIVE_POLL_INTERVAL_MS)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        fridgeCard.start()
        if (showDashboardSamples) return
        val sm = getSystemService(SensorManager::class.java)
        sensorManager = sm
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm != null && accel != null) {
            sm.registerListener(accelListener, accel, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_EDIT_DASHBOARD, false)) {
            enterDashboardEditMode()
            intent.removeExtra(EXTRA_EDIT_DASHBOARD)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EDITING, dashboardGrid.editing)
        outState.putInt(STATE_PAGE, dashboardPage)
        super.onSaveInstanceState(outState)
    }

    fun enterDashboardEditMode() = setDashboardEditing(true)

    private fun setDashboardEditing(editing: Boolean) {
        swipeHost.pagingEnabled = !editing
        dashboardGrid.setEditing(editing)
        findViewById<View>(R.id.dashboardEditToolbar).visibility = if (editing) View.VISIBLE else View.GONE
        editBackCallback.isEnabled = editing
        updatePageControls()
    }

    private fun buildPageDots() {
        val indicator = findViewById<LinearLayout>(R.id.dashboardPageDots)
        repeat(PAGE_COUNT) { page ->
            val button = FrameLayout(this).apply {
                contentDescription = getString(R.string.dashboard_page_number, page + 1, PAGE_COUNT)
                isFocusable = true
                setOnClickListener { selectDashboardPage(page) }
            }
            pageDots += View(this).also {
                it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                button.addView(it, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
            }
            indicator.addView(button, LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun selectDashboardPage(page: Int) {
        if (dashboardGrid.editing || page !in 0 until PAGE_COUNT) return
        swipeHost.resetSwipe()
        if (page == dashboardPage) return
        dashboardPage = page
        renderDashboardPage(page)
        updatePageControls()
    }

    private fun previewDashboardPage(direction: Int) {
        if (previewPage == null) outgoingScrollY = findViewById<ScrollView>(R.id.dashboardScroll).scrollY
        val page = dashboardPage + direction
        previewPage = page
        renderDashboardPage(page)
    }

    private fun finishDashboardSwipe(direction: Int) {
        val page = previewPage ?: return
        previewPage = null
        if (direction == 0) renderDashboardPage(dashboardPage, outgoingScrollY)
        else dashboardPage = page
        updatePageControls()
    }

    private fun renderDashboardPage(page: Int, scrollY: Int = 0) {
        val cards = settings.dashboardCardsForPage(page)
        val generation = ++pageRenderGeneration
        dashboardGrid.showLayout(DashboardLayout(cards))
        val scroll = findViewById<ScrollView>(R.id.dashboardScroll)
        scroll.scrollTo(0, 0)
        if (scrollY != 0) scroll.doOnNextLayout {
            if (generation == pageRenderGeneration) scroll.scrollTo(0, scrollY)
        }
        updateEmptyPageButton(cards.isEmpty())
    }

    private fun updatePageControls() {
        val editing = dashboardGrid.editing
        pageDots.forEachIndexed { index, dot ->
            val selected = index == dashboardPage
            val button = dot.parent as View
            button.isSelected = selected
            button.isEnabled = !editing
            val size = dp(if (selected) 10 else 6)
            dot.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(if (selected) R.color.heading else R.color.text_muted))
            }
        }
        updateEmptyPageButton(settings.dashboardCardsForPage(dashboardPage).isEmpty())
    }

    private fun updateEmptyPageButton(empty: Boolean) {
        findViewById<View>(R.id.dashboardEditEmpty).visibility =
            if (!dashboardGrid.editing && empty) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        regenSampler.reset()
        swipeHost.resetSwipe()
        fridgeCard.stop()
        sensorManager?.unregisterListener(accelListener)
        sensorManager = null
        super.onStop()
    }

    private fun buildDashboard() {
        if (dashboardReady) return
        val inflater = LayoutInflater.from(this)
        // Build every card once; removing a card only detaches its view, preserving live bindings.
        val blockHost = LinearLayout(this)

        val batteryBlock = inflater.inflate(R.layout.view_info_block, blockHost, false) as LinearLayout
        batteryBlock.findViewById<TextView>(R.id.infoBlockTitle).apply {
            text = getString(R.string.vehicle_info_block_battery)
            setTitleIcon(R.drawable.ic_card_battery)
        }
        batteryHealthValue = batteryBlock.findViewById(R.id.infoBlockHero)
        batteryBlock.setHeroLabel(R.string.vehicle_info_row_soh)
        batterySubtitle = batteryBlock.findViewById(R.id.infoBlockSubtitle)
        batteryMetaHost = batteryBlock.findViewById(R.id.infoBlockRows)
        socBar = addPercentRow(
            batteryMetaHost, getString(R.string.vehicle_info_row_soc), R.color.dashboard_battery_charge, glow = true,
        )
        fuelBar = addPercentRow(
            batteryMetaHost, getString(R.string.vehicle_info_row_fuel), R.color.dashboard_fuel, glow = true,
        )
        blockHost.addView(batteryBlock)

        consumptionHost = addSimpleBlock(
            blockHost,
            getString(R.string.vehicle_info_block_consumption),
            R.drawable.ic_card_consumption,
            R.string.dashboard_fuel_economy,
        )
        tabletMetaHost = addSimpleBlock(
            blockHost,
            getString(R.string.vehicle_info_block_tablet),
            R.drawable.ic_card_head_unit,
            R.string.vehicle_info_row_cpu,
        )

        val driveBlock = inflater.inflate(R.layout.view_dashboard_drive, blockHost, false)
        driveBlock.findViewById<TextView>(R.id.infoBlockTitle).apply {
            text = getString(R.string.vehicle_info_block_drive)
            setTitleIcon(R.drawable.ic_card_drive)
        }
        driveHero = driveBlock.findViewById(R.id.infoBlockHero)
        driveBlock.setHeroLabel(R.string.dashboard_speed)
        driveSubtitle = driveBlock.findViewById<TextView>(R.id.infoBlockSubtitle).apply {
            textSize = 18f
        }
        val driveMetaHost = driveBlock.findViewById<LinearLayout>(R.id.infoBlockRows)
        pedalGauge = PedalGauge(this).apply { id = R.id.dashboardPedalGauge }
        driveMetaHost.addView(pedalGauge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val parkBrakeRow = inflateInfoRow(
            inflater, driveMetaHost, getString(R.string.vehicle_info_row_park) to onOff(null),
        )
        parkBrakeRow.findViewById<TextView>(R.id.infoRowLabel).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
                weight = 0f
                marginEnd = dp(8)
            }
        }
        parkBrakeValue = parkBrakeRow.findViewById(R.id.infoRowValue)
        val footer = parkBrakeRow as LinearLayout
        footer.gravity = Gravity.BOTTOM
        footer.isBaselineAligned = false
        footer.setPaddingRelative(footer.paddingStart, footer.paddingTop, footer.paddingEnd, 0)
        pedalPeakText = TextView(this).apply {
            id = R.id.dashboardPedalPeaks
            gravity = Gravity.END
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
            setTextColor(getColor(R.color.text_primary))
        }
        footer.addView(pedalPeakText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { marginStart = dp(6) })
        bindPedalPeaks(if (showDashboardSamples) AppSettings.PedalPeaks(68f, 42f) else AppSettings.PedalPeaks(null, null))
        driveMetaHost.addView(parkBrakeRow)
        limitDashboardGraphicHeight(driveBlock, pedalGauge) { pedalGauge.maxHeight = it }
        blockHost.addView(driveBlock)

        mileageHost = addSimpleBlock(
            blockHost,
            getString(R.string.vehicle_info_block_mileage),
            R.drawable.ic_card_mileage,
            R.string.dashboard_distance_driven,
        ).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { weight = 1f }
            gravity = Gravity.BOTTOM
        }
        engineHost = addSimpleBlock(
            blockHost,
            getString(R.string.vehicle_info_block_engine),
            R.drawable.ic_card_engine,
            R.string.vehicle_info_row_rpm,
        )
        engineHost.addView(TextView(this).apply {
            setText(R.string.dashboard_rpm_last_hour)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
            setTextColor(getColor(R.color.text_muted))
        })
        rpmGraph = EngineRpmGraph(this).apply { id = R.id.dashboardRpmGraph }
        engineHost.addView(rpmGraph, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(126),
        ))

        val tyresBlock = inflater.inflate(R.layout.view_tyres_block, blockHost, false)
        tyresBlock.setDashboardTitle(R.string.vehicle_info_block_tyres, R.drawable.ic_card_tyres)
        tyresBlock.setHeroLabel(R.string.dashboard_tyre_status)
        tyresHero = tyresBlock.findViewById(R.id.infoBlockHero)
        val slotsRow = tyresBlock.findViewById<LinearLayout>(R.id.tyreSlotsRow)
        tyreUnboundHint = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@VehicleInfoActivity, R.color.text_muted))
            text = getString(R.string.vehicle_info_tyres_unbound)
            visibility = View.GONE
        }
        slotsRow.addView(
            tyreUnboundHint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        tyreSlots = BydTyreController.Corner.entries.map { corner ->
            val slot = inflater.inflate(R.layout.view_tyre_slot, slotsRow, false)
            slot.findViewById<TextView>(R.id.tyreCornerLabel).text = corner.label
            slotsRow.addView(slot)
            TyreSlot(
                root = slot,
                icon = slot.findViewById(R.id.tyreIcon),
                psi = slot.findViewById(R.id.tyrePsiText),
                temp = slot.findViewById(R.id.tyreTempText),
            )
        }
        blockHost.addView(tyresBlock)

        val attitudeBlock = inflater.inflate(R.layout.view_attitude_block, blockHost, false)
        attitudeBlock.setDashboardTitle(R.string.vehicle_info_block_attitude, R.drawable.ic_card_attitude)
        attitudePitchView = attitudeBlock.findViewById(R.id.attitudePitchHorizon)
        attitudeRollView = attitudeBlock.findViewById(R.id.attitudeRollHorizon)
        attitudePitchView.axisMode = AttitudeHorizonView.AxisMode.PITCH
        attitudeRollView.axisMode = AttitudeHorizonView.AxisMode.ROLL
        blockHost.addView(attitudeBlock)

        chassisHost = addSimpleBlock(
            blockHost,
            getString(R.string.vehicle_info_block_chassis),
            R.drawable.ic_card_chassis,
            R.string.dashboard_outside_temperature,
        )
        val fridgeView = inflater.inflate(R.layout.view_dashboard_fridge, blockHost, false)
        fridgeCard = FridgeTile(this, fridgeView, if (showDashboardSamples) DashboardSamples.fridge else null)
        val powerFlowBlock = inflater.inflate(R.layout.view_info_block, blockHost, false)
        powerFlowBlock.setPaddingRelative(powerFlowBlock.paddingStart, dp(4), powerFlowBlock.paddingEnd, dp(4))
        powerFlowBlock.setDashboardTitle(R.string.dashboard_power_flow, R.drawable.ic_card_engine)
        powerFlowPower = powerFlowBlock.findViewById(R.id.infoBlockHero)
        powerFlowPower.includeFontPadding = false
        powerFlowBlock.findViewById<TextView>(R.id.infoBlockHeroLabel).includeFontPadding = false
        powerFlowBlock.setHeroLabel(R.string.vehicle_info_row_power)
        val flowRows = powerFlowBlock.findViewById<LinearLayout>(R.id.infoBlockRows)
        flowRows.layoutParams = (flowRows.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0 }
        powerFlowView = PowerFlowView(this).apply { id = R.id.dashboardPowerFlowDiagram }
        flowRows.addView(powerFlowView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val flowFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            isBaselineAligned = false
        }
        fun footerReading(valueId: Int, label: Int, alignment: Int): TextView {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = alignment
            }
            val value = TextView(this).apply {
                id = valueId
                gravity = alignment
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
                includeFontPadding = false
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, Typeface.BOLD)
            }
            column.addView(value)
            column.addView(TextView(this).apply {
                setText(label)
                gravity = alignment
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
                includeFontPadding = false
                setTextColor(getColor(R.color.text_muted))
            })
            flowFooter.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return value
        }
        powerFlowRegen = footerReading(R.id.dashboardRegenEnergy, R.string.dashboard_flow_regen_today, Gravity.START)
        powerFlowRpm = footerReading(R.id.dashboardPowerFlowRpm, R.string.vehicle_info_block_engine, Gravity.END)
        flowRows.addView(flowFooter)
        limitDashboardGraphicHeight(powerFlowBlock, powerFlowView) { powerFlowView.maxHeight = it }
        showDailyRegen(if (showDashboardSamples) 1.275 else settings.dailyRegenKwh(LocalDate.now().toEpochDay()))
        bindPowerFlow(null, null, null)
        dashboardGrid.initialize(DashboardLayout(settings.dashboardCardsForPage(dashboardPage)), mapOf(
            DashboardCard.BATTERY to batteryBlock,
            DashboardCard.DRIVE to driveBlock,
            DashboardCard.TYRES to tyresBlock,
            DashboardCard.CONSUMPTION to consumptionHost.parent as View,
            DashboardCard.MILEAGE to mileageHost.parent as View,
            DashboardCard.ATTITUDE to attitudeBlock,
            DashboardCard.TABLET to tabletMetaHost.parent as View,
            DashboardCard.ENGINE to engineHost.parent as View,
            DashboardCard.CHASSIS to chassisHost.parent as View,
            DashboardCard.EVAKOOL_FRIDGE to fridgeView,
            DashboardCard.POWER_FLOW to powerFlowBlock,
        ))

        dashboardReady = true
    }

    private fun limitDashboardGraphicHeight(card: View, graphic: View, setMaxHeight: (Int) -> Unit) {
        val update = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (swipeHost.measuredHeight > 0 && card.isAttachedToWindow) {
                val surroundingHeight = card.measuredHeight - graphic.measuredHeight
                setMaxHeight((swipeHost.measuredHeight / 2 - surroundingHeight).coerceAtLeast(0))
            }
        }
        swipeHost.addOnLayoutChangeListener(update)
        card.addOnLayoutChangeListener(update)
    }

    internal fun bindDashboardAttitude(ax: Double, ay: Double, az: Double) {
        // The installed head unit's accelerometer axes are swapped relative to the vehicle.
        val pitch = Math.toDegrees(atan2(ay, az))
        val roll = Math.toDegrees(atan2(-ax, sqrt(ay * ay + az * az)))
        attitudePitchView.setAttitude(pitch, roll)
        attitudeRollView.setAttitude(pitch, roll)
    }

    /** Puts an icon before a card heading. The icons are bundled vectors, so no network is needed. */
    private fun TextView.setTitleIcon(@DrawableRes icon: Int) {
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
    }

    private fun View.setDashboardTitle(title: Int, @DrawableRes icon: Int) {
        findViewById<TextView>(R.id.infoBlockTitle).apply {
            setText(title)
            setTitleIcon(icon)
        }
    }

    private fun View.setHeroLabel(label: Int) {
        findViewById<TextView>(R.id.infoBlockHeroLabel).apply {
            setText(label)
            visibility = View.VISIBLE
        }
    }

    private fun LinearLayout.setHeroValue(value: CharSequence) {
        (parent as View).findViewById<TextView>(R.id.infoBlockHero).text = value
    }

    private fun addSimpleBlock(
        parent: LinearLayout, title: String, @DrawableRes icon: Int, heroLabel: Int,
    ): LinearLayout {
        val block = LayoutInflater.from(this).inflate(R.layout.view_info_block, parent, false)
        block.findViewById<TextView>(R.id.infoBlockTitle).apply {
            text = title
            setTitleIcon(icon)
        }
        block.setHeroLabel(heroLabel)
        block.findViewById<TextView>(R.id.infoBlockSubtitle).visibility = View.GONE
        parent.addView(block)
        return block.findViewById(R.id.infoBlockRows)
    }

    private fun addPercentRow(
        host: LinearLayout, label: String, @ColorRes fillColor: Int, glow: Boolean = false,
    ): PercentRow {
        val row = LayoutInflater.from(this).inflate(R.layout.view_percent_row, host, false)
        row.findViewById<TextView>(R.id.percentRowLabel).text = label
        host.addView(row)
        return PercentRow(
            value = row.findViewById(R.id.percentRowValue),
            animate = !showDashboardSamples,
            bar = row.findViewById<PercentBar>(R.id.percentRowBar).apply {
                val inset = if (glow) dp(4) else 0
                if (glow) {
                    setPadding(inset, inset, inset, inset)
                    layoutParams = layoutParams.apply { height += inset * 2 }
                }
                setFillColor(ContextCompat.getColor(this@VehicleInfoActivity, fillColor), inset.toFloat())
            },
        )
    }

    internal fun bindDashboardBattery(b: BydVehicleInfoController.BatteryInfo) {
        batteryHealthValue.text = fmt(b.sohPct, "%")
        socBar.set(b.socPct)
        fuelBar.set(b.fuelPct)
        batterySubtitle.text = listOfNotNull(
            b.remainingUsableKwh?.let {
                getString(R.string.vehicle_info_usable_fmt, trimNum(it))
            },
            b.packEnergyKwh?.let { getString(R.string.vehicle_info_pack_fmt, trimNum(it)) },
            b.capacityAh?.let { getString(R.string.vehicle_info_capacity_ah_fmt, trimNum(it)) },
        ).joinToString(" · ")
        batterySubtitle.visibility =
            if (batterySubtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE

        // Keep percent rows; refresh trailing text rows after them.
        while (batteryMetaHost.childCount > 2) {
            batteryMetaHost.removeViewAt(2)
        }
        appendTextRows(
            batteryMetaHost,
            listOf(
                getString(R.string.vehicle_info_row_ev_range) to fmt(b.evRangeKm, " km"),
                getString(R.string.vehicle_info_row_fuel_range) to fmt(b.fuelRangeKm, " km"),
                getString(R.string.vehicle_info_row_combined) to fmt(b.combinedRangeKm, " km"),
            ),
        )
    }

    private fun bindSnapshot(snap: BydVehicleInfoController.Snapshot) {
        val b = snap.battery
        bindDashboardBattery(b)
        bindPowerFlow(b.socPct, snap.dynamics.enginePowerKw, snap.dynamics.engineRpm)
        val mileage = SpannableString(TextUtils.expandTemplate(
            getText(R.string.dashboard_mileage_hero), fmt(b.evMilesKm, " km"), fmt(b.hevMilesKm, " km"),
        ))
        val muted = getColor(R.color.text_muted)
        for (label in mileage.getSpans(0, mileage.length, Annotation::class.java)) {
            if (label.key == "role" && label.value == "label") {
                mileage.setSpan(
                    ForegroundColorSpan(muted), mileage.getSpanStart(label), mileage.getSpanEnd(label),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        mileageHost.setHeroValue(mileage)
        consumptionHost.setHeroValue(fmt(b.lifetimeFuelAvg, " L/100km"))
        replaceTextRows(
            mileageHost,
            listOf(
                getString(R.string.vehicle_info_row_odo) to fmt(b.odoKm, " km"),
                getString(R.string.vehicle_info_row_drive_time) to fmt(b.driveTimeH, " h"),
                getString(R.string.vehicle_info_row_avg_speed) to fmt(b.avgSpeedKmh, " km/h"),
            ),
        )
        replaceTextRows(
            consumptionHost,
            listOf(
                getString(R.string.vehicle_info_row_life_ev) to
                    "${fmt(b.lifetimeElecKwh, " kWh")} · ${fmt(b.lifetimeElecAvg, " kWh/100km")}",
                getString(R.string.vehicle_info_row_life_charged) to fmt(b.lifetimeChargedKwh, " kWh"),
                getString(R.string.dashboard_lifetime_fuel_used) to fmt(b.lifetimeFuelL, " L"),
            ),
        )

        val d = snap.dynamics
        val rpm = d.engineRpm?.takeIf { it.isFinite() && it >= 0 }
        engineHost.setHeroValue(fmt(rpm, " rpm"))
        val now = SystemClock.elapsedRealtime()
        if (showDashboardSamples) {
            val interval = 60_000L
            rpmGraph.bind((0..60).map { minute ->
                val value = rpm ?: 0.0
                EngineRpmHistory.Sample(now - (60 - minute) * interval,
                    if (minute == 60) value else value * (0.65 + 0.35 * kotlin.math.sin(minute / 3.0)))
            }, now, interval)
        } else {
            rpmGraph.bind(rpmHistory.record(now, rpm), now, 10_000L)
        }
        chassisHost.setHeroValue(fmt(d.outsideTempC, "°C"))
        driveHero.visibility = View.VISIBLE
        driveHero.text = fmt(d.speedKmh, " km/h")
        driveSubtitle.visibility = View.VISIBLE
        val gear = SpannableString(d.gearLabel ?: getString(R.string.dashboard_value_unavailable)).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(getColor(R.color.text_primary)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        driveSubtitle.text = TextUtils.expandTemplate(getText(R.string.vehicle_info_gear_fmt), gear)
        pedalGauge.set(d.accelPedalPct, d.brakePedalPct)
        parkBrakeValue.text = onOff(d.parkBrake)

        while (engineHost.childCount > 2) engineHost.removeViewAt(2)
        appendTextRows(
            engineHost,
            listOf(
                getString(R.string.vehicle_info_row_power) to fmt(d.enginePowerKw, " kW"),
                getString(R.string.vehicle_info_row_oil) to fmt(d.oilLevel),
            ),
        )
        replaceTextRows(
            chassisHost,
            listOf(
                getString(R.string.vehicle_info_row_steer) to fmt(d.steeringDeg, "°"),
            ),
        )

        val tyres = snap.tyres
        val byCorner = tyres.corners.associateBy { it.corner }
        val tones = BydTyreController.Corner.entries.map { byCorner[it]?.statusTone() }
        tyresHero.setText(when {
            !tyres.bound -> R.string.dashboard_value_unavailable
            tones.any { it != null && it != BydTyreController.StatusTone.OK } -> R.string.dashboard_tyres_check
            tones.all { it == BydTyreController.StatusTone.OK } -> R.string.dashboard_tyres_ok
            else -> R.string.dashboard_value_unavailable
        })
        if (!tyres.bound) {
            tyreUnboundHint.visibility = View.VISIBLE
            tyreUnboundHint.text = tyres.bindError ?: getString(R.string.vehicle_info_tyres_unbound)
            tyreSlots.forEach { it.root.visibility = View.GONE }
        } else {
            tyreUnboundHint.visibility = View.GONE
            BydTyreController.Corner.entries.forEachIndexed { i, corner ->
                val slot = tyreSlots[i]
                slot.root.visibility = View.VISIBLE
                val reading = byCorner[corner]
                if (reading == null) {
                    slot.psi.text = "—"
                    slot.temp.text = "—"
                    slot.icon.setColorFilter(
                        ContextCompat.getColor(this, R.color.tyre_fault),
                    )
                } else {
                    slot.psi.text = reading.pressurePsiShort()
                    slot.temp.text = reading.temperatureLabel()
                    val color = when (reading.statusTone()) {
                        BydTyreController.StatusTone.OK -> R.color.tyre_ok
                        BydTyreController.StatusTone.UNDER -> R.color.tyre_under
                        BydTyreController.StatusTone.FAULT -> R.color.tyre_fault
                    }
                    slot.icon.setColorFilter(ContextCompat.getColor(this, color))
                }
            }
        }
    }

    private fun bindTabletSoc(soc: TabletSocReader.Snapshot) {
        if (!dashboardReady) return
        tabletMetaHost.setHeroValue(soc.cpuPct?.let { String.format("%.0f%%", it) } ?: "—")
        replaceTextRows(
            tabletMetaHost,
            listOf(
                getString(R.string.vehicle_info_row_ram) to
                    if (soc.ramUsedMb != null && soc.ramTotalMb != null) {
                        getString(
                            R.string.vehicle_info_ram_fmt,
                            soc.ramUsedMb.toString(),
                            soc.ramTotalMb.toString(),
                        )
                    } else {
                        "—"
                    },
                getString(R.string.vehicle_info_row_storage) to
                    if (soc.storageUsedGb != null && soc.storageTotalGb != null) {
                        getString(
                            R.string.vehicle_info_storage_fmt,
                            trimNum(soc.storageUsedGb),
                            trimNum(soc.storageTotalGb),
                        )
                    } else {
                        "—"
                    },
            ),
        )
    }

    private fun replaceTextRows(host: LinearLayout, rows: List<Pair<String, String>>) {
        host.removeAllViews()
        appendTextRows(host, rows)
    }

    private fun appendTextRows(host: LinearLayout, rows: List<Pair<String, String>>) {
        val inflater = LayoutInflater.from(this)
        val density = resources.displayMetrics.density
        val gap = (12 * density).toInt()
        val dividerPad = (6 * density).toInt()
        var i = 0
        while (i < rows.size) {
            val a = rows[i]
            val b = rows.getOrNull(i + 1)
            if (b != null && !isWideReading(a) && !isWideReading(b)) {
                val pair = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                pair.addView(
                    inflateInfoRow(inflater, pair, a).also {
                        it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                pair.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (1 * density).toInt().coerceAtLeast(1),
                            LinearLayout.LayoutParams.MATCH_PARENT,
                        ).apply {
                            marginStart = gap
                            marginEnd = gap
                            topMargin = dividerPad
                            bottomMargin = dividerPad
                        }
                        setBackgroundColor(ContextCompat.getColor(this@VehicleInfoActivity, R.color.divider_soft))
                    },
                )
                pair.addView(
                    inflateInfoRow(inflater, pair, b).also {
                        it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                host.addView(pair)
                i += 2
            } else {
                host.addView(inflateInfoRow(inflater, host, a))
                i += 1
            }
        }
    }

    /** Long values (e.g. lifetime consumption with avg) stay full-width. */
    private fun isWideReading(row: Pair<String, String>): Boolean =
        row.second.length > 16 || " · " in row.second

    private fun inflateInfoRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        row: Pair<String, String>,
    ): View {
        val view = inflater.inflate(R.layout.view_info_row, parent, false)
        view.findViewById<TextView>(R.id.infoRowLabel).text = row.first
        view.findViewById<TextView>(R.id.infoRowValue).text = row.second
        return view
    }

    private fun fmt(v: Double?, suffix: String = ""): String =
        if (v == null) "—" else trimNum(v) + suffix

    internal fun bindPowerFlow(soc: Double?, power: Double?, rpm: Double?) {
        powerFlowView.bind(soc, power, rpm)
        val powerText = powerFlowView.powerText()
        val rpmText = powerFlowView.rpmText()
        if (powerFlowPower.text != powerText) powerFlowPower.text = powerText
        if (powerFlowRpm.text != rpmText) powerFlowRpm.text = rpmText
    }

    private fun showDailyRegen(kwh: Double) {
        val text = getString(R.string.dashboard_flow_kwh, regenFormat.format(kwh))
        if (powerFlowRegen.text != text) powerFlowRegen.text = text
    }

    private fun bindPedalPeaks(peaks: AppSettings.PedalPeaks) {
        if (displayedPedalPeaks == peaks) return
        displayedPedalPeaks = peaks
        pedalGauge.setPeaks(peaks.accelerator, peaks.brake)
        fun percent(value: Float?) = value?.let { String.format("%.0f%%", it) }
            ?: getString(R.string.dashboard_value_unavailable)
        pedalPeakText.text = getString(
            R.string.dashboard_pedal_peaks, percent(peaks.accelerator), percent(peaks.brake),
        )
    }

    private fun onOff(v: Int?): String = when (v) {
        null -> "—"
        0 -> "Off"
        else -> "On"
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format("%.1f", v)

    private data class PercentRow(
        val value: TextView,
        val bar: PercentBar,
        val animate: Boolean,
    ) {
        fun set(pct: Double?) {
            value.text = if (pct == null) "—" else String.format("%.0f%%", pct)
            bar.setPercent(pct, animate)
        }
    }

    private data class TyreSlot(
        val root: View,
        val icon: ImageView,
        val psi: TextView,
        val temp: TextView,
    )

    companion object {
        const val EXTRA_EDIT_DASHBOARD = "com.sphy.airconcontroller.EDIT_DASHBOARD"
        internal const val EXTRA_LIVE_DASHBOARD = "com.sphy.airconcontroller.LIVE_DASHBOARD"
        private const val STATE_EDITING = "dashboard_editing"
        private const val STATE_PAGE = "dashboard_page"
        private const val PAGE_COUNT = 5
        private val rpmHistory = EngineRpmHistory()
        private const val POLL_INTERVAL_MS = 2_000L
        private const val DRIVE_POLL_INTERVAL_MS = 200L
    }
}
