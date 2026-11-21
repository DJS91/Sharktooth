package com.sphy.airconcontroller

import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.Annotation
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sphy.airconcontroller.byd.BydTyreController
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.byd.BydRegenController
import com.sphy.airconcontroller.byd.TabletSocReader
import com.sphy.airconcontroller.fridge.FridgeTile
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.DashboardDetailsDialog
import com.sphy.airconcontroller.ui.DashboardHistory
import com.sphy.airconcontroller.ui.DashboardRecorder
import com.sphy.airconcontroller.ui.DashboardHistoryGraph
import com.sphy.airconcontroller.ui.DashboardMetrics
import com.sphy.airconcontroller.ui.AttitudeHorizonView
import com.sphy.airconcontroller.ui.DashboardCard
import com.sphy.airconcontroller.ui.DashboardGrid
import com.sphy.airconcontroller.ui.DashboardLayout
import com.sphy.airconcontroller.ui.DashboardSamples
import com.sphy.airconcontroller.ui.DashboardTitleView
import com.sphy.airconcontroller.ui.TripUsageTracker
import com.sphy.airconcontroller.ui.DashboardSwipeHost
import com.sphy.airconcontroller.ui.PercentBar
import com.sphy.airconcontroller.ui.PowerFlowView
import com.sphy.airconcontroller.ui.PedalGauge
import com.sphy.airconcontroller.ui.TyreInflation
import com.sphy.airconcontroller.ui.EngineRpmGraph
import com.sphy.airconcontroller.ui.EngineRpmHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.text.NumberFormat
import java.util.Date
import kotlin.math.roundToInt

/** Live vehicle cards in a saved, editable three-column dashboard. */
open class VehicleInfoActivity : HomeActivity() {
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

    private lateinit var socBar: PercentRow
    private lateinit var batteryHealthValue: TextView
    private lateinit var tyresHero: TextView
    private lateinit var fuelBar: PercentRow
    private lateinit var batterySubtitle: TextView
    private lateinit var batteryMetaHost: LinearLayout

    private lateinit var pedalGauge: PedalGauge
    private lateinit var rpmGraph: EngineRpmGraph
    private var rpmDialog: AlertDialog? = null
    private var rpmDialogGraphs = emptyList<EngineRpmGraph>()
    private var detailsDialog: DashboardDetailsDialog? = null
    private var cardViews = emptyMap<DashboardCard, View>()
    private var historySamples = emptyList<DashboardHistory.Sample>()
    private var historyGraphs = emptyList<DashboardHistoryGraph>()
    private var historyNote: TextView? = null
    private var lastFuelFillDetails: TextView? = null
    private var historyError: String? = null
    private var dailyRegen = 0.0
    private var latestPitch: Double? = null
    private var latestRoll: Double? = null
    private val regenController by lazy { BydRegenController(this) }
    private var previewRegenMode = BydRegenController.Mode.STANDARD
    private var lastLongGraphUpdate = 0L
    private var hasLiveRpm = false
    private val recorder by lazy { OpenDiKeyApp.from(this).dashboardRecorder }
    private val rpmHistory by lazy { if (showDashboardSamples) EngineRpmHistory() else recorder.rpmHistory }
    private lateinit var driveHero: TextView
    private lateinit var driveSubtitle: TextView
    private lateinit var parkBrakeValue: TextView
    private lateinit var powerFlowView: PowerFlowView
    private lateinit var powerFlowRpm: TextView
    private lateinit var powerFlowPower: TextView
    private lateinit var powerFlowRegen: TextView
    private var powerFlowSoc: Double? = null
    private var powerFlowEngineRpm: Double? = null
    private var powerFlowPowerKw: Double? = null
    private var powerFlowSpeedKmh: Double? = null
    private var hasLivePower = false
    private var latestDriveLive: BydVehicleInfoController.DriveLive? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle_info)

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
            bindSnapshot(DashboardSamples.vehicle)
            bindTabletSoc(DashboardSamples.headUnit)
            bindDashboardAttitude(ax = 1.2, ay = -0.8, az = 9.7)
            seedPreviewHistory()
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) {
                        bindRpmLive(DashboardSamples.vehicle.dynamics.engineRpm)
                        delay(RPM_POLL_INTERVAL_MS)
                    }
                }
            }
            return
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    recorder.snapshot.collect { reading ->
                        if (reading != null) {
                            showDailyRegen(reading.regen)
                            bindSnapshot(reading.vehicle)
                            bindTabletSoc(reading.tablet)
                            bindLastFuelFill(settings.fuelFillState.lastFillAtMs)
                            historySamples = reading.history
                            val error = reading.historyError
                            if (error != null) reportHistoryError(error) else historyError = null
                            refreshHistoryGraphs(reading.time)
                        }
                    }
                }
                launch {
                    recorder.drive.collect { reading ->
                        if (reading != null) bindDriveLive(reading.reading, reading.peaks)
                    }
                }
                launch { recorder.power.collect { bindPowerLive(it) } }
                launch {
                    recorder.rpm.collect { reading ->
                        if (reading != null) {
                            hasLiveRpm = true
                            updateRpm(reading.rpm, reading.timeMs, record = false)
                        }
                    }
                }
                launch {
                    recorder.attitude.collect { reading ->
                        if (reading != null) bindAttitude(reading)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        fridgeCard.start()
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
        if (editing) detailsDialog?.dialog?.dismiss()
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
        detailsDialog?.dialog?.dismiss()
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
        detailsDialog?.dialog?.dismiss()
        rpmDialog?.dismiss()
        swipeHost.resetSwipe()
        fridgeCard.stop()
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
        val batteryTitle = batteryBlock.findViewById<TextView>(R.id.infoBlockTitle)
        val batteryHeader = batteryTitle.parent as LinearLayout
        val titleColumnParams = batteryTitle.layoutParams
        batteryHeader.removeView(batteryTitle)
        batteryBlock.removeView(batterySubtitle)
        val batteryTitleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        batteryHeader.addView(batteryTitleColumn, 0, titleColumnParams)
        batteryTitleColumn.addView(batteryTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        batteryTitleColumn.addView(batterySubtitle)
        batteryMetaHost = batteryBlock.findViewById(R.id.infoBlockRows)
        socBar = addPercentRow(
            batteryMetaHost, getString(R.string.vehicle_info_row_soc), R.color.dashboard_battery_charge, glow = true,
        )
        fuelBar = addPercentRow(
            batteryMetaHost, getString(R.string.vehicle_info_row_fuel), R.color.dashboard_accent_blue, glow = true,
        )
        batterySubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX,
            batteryMetaHost.getChildAt(0).findViewById<TextView>(R.id.percentRowLabel).textSize)
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
        limitDashboardGraphicHeight(driveBlock, pedalGauge)
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
            setText(R.string.dashboard_rpm_last_ten_seconds)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
            setTextColor(getColor(R.color.text_muted))
        })
        rpmGraph = EngineRpmGraph(this).apply { id = R.id.dashboardRpmGraph }
        engineHost.addView(rpmGraph, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(126),
        ))
        (engineHost.parent as View).setOnClickListener { showRpmHistory() }

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
                status = slot.findViewById(R.id.tyreStatusIndicator),
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
        val powerFlowBlock = inflater.inflate(R.layout.view_dashboard_power_flow, blockHost, false) as FrameLayout
        powerFlowBlock.setDashboardTitle(R.string.dashboard_power_flow, R.drawable.ic_card_engine)
        powerFlowBlock.findViewById<DashboardTitleView>(R.id.infoBlockTitle).enableSolidHalo()
        powerFlowRegen = powerFlowBlock.findViewById(R.id.infoBlockHero)
        powerFlowRegen.includeFontPadding = false
        powerFlowBlock.findViewById<TextView>(R.id.infoBlockHeroLabel).apply {
            includeFontPadding = false
        }
        powerFlowBlock.setHeroLabel(R.string.dashboard_flow_regen_today)
        val flowRows = powerFlowBlock.findViewById<LinearLayout>(R.id.infoBlockRows)
        powerFlowView = PowerFlowView(this).apply { id = R.id.dashboardPowerFlowDiagram }
        powerFlowBlock.addView(powerFlowView, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        powerFlowRpm = TextView(this).apply {
            id = R.id.dashboardPowerFlowRpm
            gravity = Gravity.START
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
            includeFontPadding = false
            setTextColor(getColor(R.color.text_primary))
        }
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
        powerFlowPower = footerReading(R.id.dashboardFlowPower, R.string.vehicle_info_row_power, Gravity.START)
        (powerFlowPower.parent as LinearLayout).addView(powerFlowRpm, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        flowRows.addView(flowFooter)
        showDailyRegen(if (showDashboardSamples) 1.275 else settings.dailyRegenKwh(LocalDate.now().toEpochDay()))
        bindPowerFlow(null, null, null, null)
        cardViews = mapOf(
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
        )
        cardViews.forEach { (card, view) ->
            if (card != DashboardCard.ENGINE && card != DashboardCard.EVAKOOL_FRIDGE) {
                view.setOnClickListener { showCardDetails(card) }
            }
        }
        dashboardGrid.initialize(DashboardLayout(settings.dashboardCardsForPage(dashboardPage)), cardViews)

        dashboardReady = true
    }

    private fun limitDashboardGraphicHeight(card: View, graphic: PedalGauge) {
        val update = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (swipeHost.measuredHeight > 0 && card.isAttachedToWindow && detailsDialog == null) {
                val surroundingHeight = card.measuredHeight - graphic.measuredHeight
                val fullHeight = minOf(graphic.naturalHeight + surroundingHeight, swipeHost.measuredHeight / 2)
                graphic.maxHeight = ((fullHeight * 0.9025f).roundToInt() - surroundingHeight).coerceAtLeast(0)
            }
        }
        swipeHost.addOnLayoutChangeListener(update)
        card.addOnLayoutChangeListener(update)
    }

    internal fun bindDashboardAttitude(ax: Double, ay: Double, az: Double) {
        bindAttitude(DashboardRecorder.Attitude.fromAcceleration(ax, ay, az))
    }

    private fun bindAttitude(attitude: DashboardRecorder.Attitude) {
        val (pitch, roll) = attitude
        latestPitch = pitch
        latestRoll = roll
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
            tripUsage = row.findViewById(R.id.percentRowTripUsage),
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

    internal fun bindDashboardBattery(
        b: BydVehicleInfoController.BatteryInfo,
        startCharge: Double? = null,
        startFuel: Double? = null,
        trip: TripUsageTracker.State? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        batteryHealthValue.text = fmt(b.sohPct, "%")
        socBar.set(b.socPct, startCharge)
        fuelBar.set(b.fuelPct, startFuel)
        val elapsedMinutes = trip?.startedAtMs?.let {
            ((nowMs - it).coerceAtLeast(0L) / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val started = if (elapsedMinutes == null) {
            getString(R.string.dashboard_trip_started_unknown)
        } else {
            resources.getQuantityString(R.plurals.dashboard_trip_started_minutes, elapsedMinutes, elapsedMinutes)
        }
        val distance = trip?.distanceKm(b.odoKm)?.let { trimNum(it) }
            ?: getString(R.string.dashboard_value_unavailable)
        batterySubtitle.text = "$started\n${getString(R.string.dashboard_trip_traveled, distance)}"
        batterySubtitle.visibility = View.VISIBLE

        // Keep percent rows; refresh trailing text rows after them.
        while (batteryMetaHost.childCount > 2) {
            batteryMetaHost.removeViewAt(2)
        }
        appendTextColumns(
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
        val trip = if (showDashboardSamples) DashboardSamples.tripUsage else settings.tripUsageState
        bindDashboardBattery(b, trip.startCharge, trip.startFuel, trip)
        val live = latestDriveLive
        bindPowerFlow(
            b.socPct,
            if (hasLivePower) powerFlowPowerKw else snap.dynamics.enginePowerKw,
            if (hasLiveRpm) powerFlowEngineRpm else snap.dynamics.engineRpm,
            if (live != null) live.speedKmh else snap.dynamics.speedKmh,
        )
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
        if (showDashboardSamples) {
            val now = SystemClock.elapsedRealtime()
            val rpm = d.engineRpm ?: 0.0
            for (i in 0..36_000) {
                rpmHistory.record(now - EngineRpmHistory.MAX_HISTORY_MS + i * 50L,
                    if (i == 36_000) rpm else rpm * (0.65 + 0.35 * kotlin.math.sin(i / 60.0)))
            }
            updateRpm(rpm, now, record = false)
        } else if (!hasLiveRpm) {
            updateRpm(d.engineRpm, SystemClock.elapsedRealtime(), record = false)
        }
        chassisHost.setHeroValue(fmt(d.outsideTempC, "°C"))
        driveHero.visibility = View.VISIBLE
        driveHero.text = fmt(if (live != null) live.speedKmh else d.speedKmh, " km/h")
        driveSubtitle.visibility = View.VISIBLE
        val gear = SpannableString(d.gearLabel ?: getString(R.string.dashboard_value_unavailable)).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(getColor(R.color.text_primary)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        driveSubtitle.text = TextUtils.expandTemplate(getText(R.string.vehicle_info_gear_fmt), gear)
        pedalGauge.set(
            if (live != null) live.accelPedalPct else d.accelPedalPct,
            if (live != null) live.brakePedalPct else d.brakePedalPct,
        )
        parkBrakeValue.text = onOff(d.parkBrake)

        while (engineHost.childCount > 2) engineHost.removeViewAt(2)
        val oilRow = inflateInfoRow(layoutInflater, engineHost,
            getString(R.string.vehicle_info_row_oil) to fmt(d.oilLevel))
        oilRow.findViewById<TextView>(R.id.infoRowLabel).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
                weight = 0f
                marginEnd = dp(8)
            }
        }
        engineHost.addView(oilRow)
        replaceTextRows(
            chassisHost,
            listOf(
                getString(R.string.vehicle_info_row_steer) to fmt(d.steeringDeg, "°"),
            ),
        )

        bindDashboardTyres(snap.tyres)
    }

    internal fun bindDashboardTyres(tyres: BydTyreController.TyreSnapshot) {
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
                val inflation = TyreInflation.fromPsi(reading?.pressurePsi())
                slot.psi.text = reading?.pressurePsiShort() ?: "—"
                slot.temp.text = reading?.temperatureLabel() ?: "—"
                if (slot.inflation != inflation) {
                    if (inflation == null) slot.icon.setImageDrawable(null)
                    else slot.icon.setImageResource(inflation.image)
                    slot.inflation = inflation
                }
                slot.icon.visibility = if (inflation == null) View.INVISIBLE else View.VISIBLE
                slot.icon.contentDescription = if (inflation == null) {
                    getString(R.string.tyre_inflation_unknown, corner.label)
                } else getString(R.string.tyre_inflation_description, corner.label, slot.psi.text,
                    getString(inflation.description))
                val (color, status) = when (reading?.statusTone()) {
                    BydTyreController.StatusTone.OK -> R.color.tyre_ok to R.string.tyre_status_ok
                    BydTyreController.StatusTone.UNDER -> R.color.tyre_under to R.string.tyre_status_under
                    else -> R.color.tyre_fault to R.string.tyre_status_fault
                }
                slot.status.backgroundTintList = ColorStateList.valueOf(getColor(color))
                slot.status.contentDescription = getString(status)
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
        var i = 0
        while (i < rows.size) {
            val a = rows[i]
            val b = rows.getOrNull(i + 1)
            if (b != null && !isWideReading(a) && !isWideReading(b)) {
                appendTextColumns(host, listOf(a, b))
                i += 2
            } else {
                host.addView(inflateInfoRow(LayoutInflater.from(this), host, a))
                i += 1
            }
        }
    }

    private fun appendTextColumns(host: LinearLayout, rows: List<Pair<String, String>>) {
        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) columns.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1).coerceAtLeast(1),
                    LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    marginStart = dp(12)
                    marginEnd = dp(12)
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
                setBackgroundColor(ContextCompat.getColor(this@VehicleInfoActivity, R.color.divider_soft))
            })
            columns.addView(inflateInfoRow(LayoutInflater.from(this), columns, row).also {
                it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        host.addView(columns)
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

    internal fun bindPowerFlow(soc: Double?, power: Double?, rpm: Double?, speedKmh: Double?) {
        powerFlowSoc = soc
        powerFlowEngineRpm = rpm
        powerFlowPowerKw = power
        powerFlowSpeedKmh = speedKmh
        powerFlowView.bind(soc, power, rpm, speedKmh)
        val powerText = powerFlowView.powerText()
        val rpmText = powerFlowView.rpmText()
        if (powerFlowPower.text != powerText) powerFlowPower.text = powerText
        if (powerFlowRpm.text != rpmText) powerFlowRpm.text = rpmText
    }

    internal fun bindDriveLive(drive: BydVehicleInfoController.DriveLive, peaks: AppSettings.PedalPeaks? = null) {
        latestDriveLive = drive
        if (!dashboardReady) return
        driveHero.visibility = View.VISIBLE
        driveHero.text = fmt(drive.speedKmh, " km/h")
        pedalGauge.set(drive.accelPedalPct, drive.brakePedalPct)
        bindPedalPeaks(peaks ?: settings.recordPedalPeaks(
            LocalDate.now().toEpochDay(), drive.accelPedalPct, drive.brakePedalPct,
        ))
        bindPowerFlow(powerFlowSoc, powerFlowPowerKw, powerFlowEngineRpm, drive.speedKmh)
    }

    internal fun bindPowerLive(power: Double?) {
        hasLivePower = true
        if (!dashboardReady) return
        bindPowerFlow(powerFlowSoc, power, powerFlowEngineRpm, powerFlowSpeedKmh)
    }

    internal fun bindRpmLive(rpm: Double?, now: Long = SystemClock.elapsedRealtime()) {
        hasLiveRpm = true
        if (dashboardReady) updateRpm(rpm, now, record = true)
    }

    private fun updateRpm(rpm: Double?, now: Long, record: Boolean) {
        if (record) rpmHistory.record(now, rpm)
        val hero = (engineHost.parent as View).findViewById<TextView>(R.id.infoBlockHero)
        val text = fmt(rpm, " rpm")
        if (hero.text.toString() != text) hero.text = text
        bindPowerFlow(powerFlowSoc, powerFlowPowerKw, rpm, powerFlowSpeedKmh)
        rpmGraph.bind(rpmHistory.since(now, EngineRpmHistory.TEN_SECONDS_MS), now, 500L)
        rpmDialogGraphs.firstOrNull()?.bind(rpmHistory.since(now, EngineRpmHistory.TEN_SECONDS_MS), now, 500L)
        if (rpmDialogGraphs.isNotEmpty() && now - lastLongGraphUpdate >= 1_000L) {
            rpmDialogGraphs[1].bind(rpmHistory.since(now, EngineRpmHistory.FIVE_MINUTES_MS), now, 500L)
            rpmDialogGraphs[2].bind(rpmHistory.since(now, EngineRpmHistory.MAX_HISTORY_MS), now, 500L)
            lastLongGraphUpdate = now
        }
    }

    private fun showRpmHistory() {
        if (dashboardGrid.editing || detailsDialog != null) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        content.addView(TextView(this).apply {
            setText(R.string.dashboard_rpm_history_title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
        })
        val ranges = listOf(
            Triple(EngineRpmHistory.TEN_SECONDS_MS, R.string.dashboard_rpm_last_ten_seconds, R.id.dashboardRpmTenSecondsGraph),
            Triple(EngineRpmHistory.FIVE_MINUTES_MS, R.string.dashboard_rpm_last_five_minutes, R.id.dashboardRpmFiveMinutesGraph),
            Triple(EngineRpmHistory.MAX_HISTORY_MS, R.string.dashboard_rpm_last_thirty_minutes, R.id.dashboardRpmThirtyMinutesGraph),
        )
        val now = SystemClock.elapsedRealtime()
        rpmDialogGraphs = ranges.map { (range, label, graphId) ->
            content.addView(TextView(this).apply {
                setText(label)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle2)
            })
            EngineRpmGraph(this, range).apply {
                id = graphId
                bind(rpmHistory.since(now, range), now, 500L)
                content.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }
        detailsDialog = DashboardDetailsDialog(this, cardViews.getValue(DashboardCard.ENGINE),
            content, null, settings.sidebarSide) {
            rpmDialogGraphs = emptyList()
            rpmDialog = null
            detailsDialog = null
        }
        rpmDialog = detailsDialog?.dialog
        lastLongGraphUpdate = now
    }

    private fun showDailyRegen(kwh: Double) {
        dailyRegen = kwh
        val text = getString(R.string.dashboard_flow_kwh, regenFormat.format(kwh))
        if (powerFlowRegen.text != text) powerFlowRegen.text = text
    }

    private fun reportHistoryError(error: Exception) {
        Log.e("DashboardHistory", "Cannot record or load daily history", error)
        val message = getString(R.string.dashboard_history_storage_error, error.message ?: error.javaClass.simpleName)
        if (historyError == null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        historyError = message
    }

    private fun refreshHistoryGraphs(now: Long = System.currentTimeMillis()) {
        historyGraphs.forEach { it.bind(historySamples, now) }
        historyNote?.text = historyError ?: getString(if (showDashboardSamples)
            R.string.dashboard_history_preview else R.string.dashboard_history_note)
    }

    private fun seedPreviewHistory() {
        val now = System.currentTimeMillis()
        val start = DashboardHistory.dayBounds(now).first
        val values = DashboardMetrics.values(DashboardSamples.vehicle, DashboardSamples.headUnit,
            dailyRegen, latestPitch, latestRoll,
            DashboardSamples.tripUsage.distanceKm(DashboardSamples.vehicle.battery.odoKm), null)
        historySamples = (0..120).map { index ->
            val fraction = index / 120.0
            DashboardHistory.Sample(start + ((now - start) * fraction).toLong(),
                values.mapValues { (key, value) ->
                    if (key == "regen") value?.times(fraction)
                    else value?.times(0.95 + 0.05 * kotlin.math.cos(index / 8.0))
                })
        }
    }

    private fun showCardDetails(card: DashboardCard) {
        if (dashboardGrid.editing || detailsDialog != null) return
        val graphs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val metrics = if (card == DashboardCard.TYRES) BydTyreController.Corner.entries.flatMap { corner ->
            listOf("pressure_${corner.name}" to getString(R.string.dashboard_history_metric,
                getString(R.string.dashboard_history_tyre_pressure, corner.label), "psi"),
                "temperature_${corner.name}" to getString(R.string.dashboard_history_metric,
                    getString(R.string.dashboard_history_tyre_temperature, corner.label), "°C"))
        } else DashboardMetrics.forCard(card).map { it.key to it.title(this) }
        val graphList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyGraphs = metrics.map { (key, label) ->
            graphList.addView(TextView(this).apply {
                text = getString(R.string.dashboard_history_today, label)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
            })
            DashboardHistoryGraph(this, key, label,
                if (showDashboardSamples) 25 * 3_600_000L else DashboardHistory.GAP_MS).apply {
                graphList.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    if (metrics.size == 1) (resources.displayMetrics.heightPixels * 0.65f).toInt() else dp(200)))
            }
        }
        graphs.addView(ScrollView(this).apply { addView(graphList) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        historyNote = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
            graphs.addView(this)
        }
        refreshHistoryGraphs()
        val controls = when (card) {
            DashboardCard.POWER_FLOW -> buildRegenControls()
            DashboardCard.BATTERY -> TextView(this).apply {
                id = R.id.dashboardLastFuelFill
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
                lastFuelFillDetails = this
                bindLastFuelFill(settings.fuelFillState.lastFillAtMs)
            }
            else -> null
        }
        detailsDialog = DashboardDetailsDialog(this, cardViews.getValue(card), graphs, controls, settings.sidebarSide,
            fitPreview = card == DashboardCard.POWER_FLOW) {
            historyGraphs = emptyList()
            historyNote = null
            lastFuelFillDetails = null
            detailsDialog = null
        }
    }

    internal fun bindLastFuelFill(timestamp: Long?) {
        val dateTime = timestamp?.let {
            val date = Date(it)
            "${android.text.format.DateFormat.getDateFormat(this).format(date)} " +
                android.text.format.DateFormat.getTimeFormat(this).format(date)
        } ?: getString(R.string.dashboard_fuel_fill_unknown)
        lastFuelFillDetails?.text = getString(R.string.dashboard_last_fuel_fill, dateTime)
    }

    private fun buildRegenControls(): View {
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(TextView(this).apply { setText(R.string.dashboard_regen_mode) })
        val row = MaterialButtonToggleGroup(this).apply { isSingleSelection = true }
        val status = TextView(this).apply {
            id = R.id.dashboardRegenStatus
            setText(if (showDashboardSamples) R.string.dashboard_history_preview else R.string.dashboard_regen_loading)
        }
        var busy = false
        var current: BydRegenController.Mode? = if (showDashboardSamples) previewRegenMode else null
        val buttons = BydRegenController.Mode.entries.associateWith { mode ->
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = if (mode == BydRegenController.Mode.STANDARD) R.id.dashboardRegenStandard else R.id.dashboardRegenLarger
                setText(if (mode == BydRegenController.Mode.STANDARD)
                    R.string.dashboard_regen_standard else R.string.dashboard_regen_larger)
                row.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        fun render() {
            val checked = current?.let { buttons.getValue(it).id }
            if (checked == null) row.clearChecked() else row.check(checked)
            buttons.forEach { (mode, button) ->
                button.isEnabled = !busy && current != null && current != mode
                button.isSelected = current == mode
            }
        }
        buttons.forEach { (mode, button) ->
            button.setOnClickListener {
                if (showDashboardSamples) {
                    previewRegenMode = mode
                    current = mode
                    status.setText(R.string.dashboard_history_preview)
                    render()
                } else {
                    busy = true
                    render()
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) { regenController.setMode(mode) }
                        val reading = withContext(Dispatchers.IO) { regenController.read() }
                        current = reading.mode
                        busy = false
                        status.text = if (result.success) "" else getString(R.string.dashboard_regen_failed, result.detail)
                        render()
                    }
                }
            }
        }
        controls.addView(row)
        controls.addView(status)
        render()
        if (!showDashboardSamples) lifecycleScope.launch {
            val reading = withContext(Dispatchers.IO) { regenController.read() }
            current = reading.mode
            status.text = if (current == null) getString(R.string.dashboard_regen_unavailable, reading.detail) else ""
            render()
        }
        return controls
    }

    override fun onDestroy() {
        detailsDialog?.dialog?.dismiss()
        super.onDestroy()
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
        val tripUsage: TextView,
        val bar: PercentBar,
        val animate: Boolean,
    ) {
        fun set(pct: Double?, start: Double?) {
            val current = pct?.takeIf { it.isFinite() && it in 0.0..100.0 }
            val baseline = start?.takeIf { it.isFinite() && it in 0.0..100.0 }
            value.text = if (current == null) "—" else String.format("%.0f%%", current)
            val spent = if (current != null && baseline != null) {
                (baseline.roundToInt() - current.roundToInt()).coerceAtLeast(0)
            } else 0
            tripUsage.visibility = if (spent > 0) View.VISIBLE else View.GONE
            tripUsage.text = if (spent > 0) value.context.getString(
                R.string.dashboard_trip_percent_spent, String.format("%d%%", spent),
            ) else ""
            bar.setStartPercent(baseline)
            bar.setPercent(current, animate)
        }
    }

    private data class TyreSlot(
        val root: View,
        val icon: ImageView,
        val status: View,
        val psi: TextView,
        val temp: TextView,
        var inflation: TyreInflation? = null,
    )

    companion object {
        const val EXTRA_EDIT_DASHBOARD = "com.sphy.airconcontroller.EDIT_DASHBOARD"
        internal const val EXTRA_LIVE_DASHBOARD = "com.sphy.airconcontroller.LIVE_DASHBOARD"
        private const val STATE_EDITING = "dashboard_editing"
        private const val STATE_PAGE = "dashboard_page"
        private const val PAGE_COUNT = 5
        private const val RPM_POLL_INTERVAL_MS = 50L
    }
}
