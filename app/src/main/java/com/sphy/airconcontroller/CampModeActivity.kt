package com.sphy.airconcontroller

import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.Annotation
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sphy.airconcontroller.byd.BydSaveSocController
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.camp.CampModeController
import com.sphy.airconcontroller.camp.CampModePreviewActions
import com.sphy.airconcontroller.camp.CampModeSession
import com.sphy.airconcontroller.camp.CampStep
import com.sphy.airconcontroller.camp.batteryAtCutoff
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

class CampModeActivity : HomeActivity() {
    private var previousStep: CampStep? = null
    private var reminderDialogPresented = false
    internal var headlightReminderDialog: AlertDialog? = null
        private set
    private lateinit var controller: CampModeController
    private var draggingSaveSoc = false
    private val showPreview by lazy {
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            !intent.getBooleanExtra(EXTRA_LIVE_CAMP_MODE, false)
    }
    private var previewSession: CampModeSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camp_mode)
        findViewById<TextView>(R.id.campModeHeadlightNote).text = SpannableString(
            getText(R.string.camp_mode_headlight_note),
        ).apply {
            for (annotation in getSpans(0, length, Annotation::class.java)) {
                if (annotation.key != "role" || annotation.value != "highlight") continue
                val start = getSpanStart(annotation)
                val end = getSpanEnd(annotation)
                setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(getColor(R.color.status_warning)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (showPreview) {
            bindPreview()
            return
        }
        controller = CampModeController.get(this)
        val enable = findViewById<Button>(R.id.campModeEnable)
        enable.setOnClickListener {
            if (controller.state.value.active) {
                controller.exit()
            } else {
                controller.enter()
            }
        }
        findViewById<Button>(R.id.campModeStopWindows).setOnClickListener { controller.stopWindows() }
        findViewById<Button>(R.id.campModeCutoffDecrease).setOnClickListener { controller.adjustBatteryCutoff(-1) }
        findViewById<Button>(R.id.campModeCutoffIncrease).setOnClickListener { controller.adjustBatteryCutoff(1) }
        bindSaveSoc()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { controller.saveSoc.collect(::renderSaveSoc) }
                launch {
                    while (true) {
                        controller.refreshSaveSoc()
                        delay(2000)
                    }
                }
                launch { controller.state.collect(::renderChecklist) }
                combine(controller.state, controller.vehicleStatus, controller.batteryCutoff) { state, vehicle, cutoff ->
                    Triple(state, vehicle, cutoff)
                }.collect { (state, vehicle, cutoff) ->
                    renderVehicleStatus(vehicle)
                    renderControls(state, vehicle.gear?.confirmedPark == true, vehicle.soc, cutoff)
                }
            }
        }
    }

    private fun bindPreview() {
        val session = CampModeSession(CampModePreviewActions())
        previewSession = session
        val cutoff = MutableStateFlow(10)
        val vehicle = CampModeController.VehicleStatus(BydVehicleInfoController.GearReads(1, 1), 76.0)
        findViewById<View>(R.id.campModePreviewNotice).visibility = View.VISIBLE
        renderSaveSoc(CampModeController.SaveSocStatus())
        findViewById<Button>(R.id.campModeEnable).setOnClickListener {
            lifecycleScope.launch {
                if (session.state.value.active) session.exit() else session.enter()
            }
        }
        findViewById<Button>(R.id.campModeStopWindows).setOnClickListener {
            lifecycleScope.launch { session.stopWindows() }
        }
        findViewById<Button>(R.id.campModeCutoffDecrease).setOnClickListener {
            cutoff.value = (cutoff.value - 1).coerceAtLeast(1)
        }
        findViewById<Button>(R.id.campModeCutoffIncrease).setOnClickListener {
            cutoff.value = (cutoff.value + 1).coerceAtMost(99)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(session.state, cutoff) { state, selected -> state to selected }
                    .collect { (state, selected) ->
                        renderVehicleStatus(vehicle)
                        renderChecklist(state)
                        renderControls(state, true, vehicle.soc, selected)
                    }
            }
        }
    }

    override fun wakeCampSession(): Boolean {
        if (!showPreview) return super.wakeCampSession()
        val session = previewSession ?: return false
        if (!session.state.value.awaitingWake) return false
        lifecycleScope.launch { session.onScreenWake() }
        return true
    }

    private fun bindSaveSoc() {
        findViewById<SwitchMaterial>(R.id.campSaveSocSwitch).setOnClickListener {
            val current = controller.saveSoc.value.reading?.enabled
            renderSaveSoc(controller.saveSoc.value)
            if (current != null) controller.setSaveSocEnabled(!current)
        }
        findViewById<SeekBar>(R.id.campSaveSocTarget).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {
                    draggingSaveSoc = true
                }

                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser || !bar.isEnabled) return
                    if (draggingSaveSoc) {
                        findViewById<TextView>(R.id.campSaveSocValue).text = percent(progress.toDouble())
                    } else {
                        commitSaveSocTarget(progress)
                    }
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    draggingSaveSoc = false
                    if (bar.isEnabled) commitSaveSocTarget(bar.progress)
                    else renderSaveSoc(controller.saveSoc.value)
                }
            },
        )
    }

    private fun commitSaveSocTarget(target: Int) {
        val status = controller.saveSoc.value
        renderSaveSoc(status)
        if (target != status.reading?.target) controller.setSaveSocTarget(target)
    }

    internal fun renderSaveSoc(status: CampModeController.SaveSocStatus) {
        val reading = status.reading
        findViewById<SwitchMaterial>(R.id.campSaveSocSwitch).apply {
            isChecked = reading?.enabled == true
            isEnabled = reading?.enabled != null && !status.saving
        }
        val slider = findViewById<SeekBar>(R.id.campSaveSocTarget)
        slider.isEnabled = reading?.enabled == true && reading.target != null && !status.saving
        if (!draggingSaveSoc || !slider.isEnabled) {
            slider.max = reading?.maximum ?: BydSaveSocController.MAX_TARGET
            slider.progress = reading?.target ?: BydSaveSocController.MIN_TARGET
            findViewById<TextView>(R.id.campSaveSocValue).text =
                reading?.target?.let { percent(it.toDouble()) } ?: getString(R.string.camp_save_soc_unavailable)
        }
    }

    private fun percent(value: Double): String =
        NumberFormat.getPercentInstance(resources.configuration.locales[0]).apply {
            maximumFractionDigits = 1
        }.format(value / 100)

    internal fun renderVehicleStatus(vehicle: CampModeController.VehicleStatus) {
        val gear = vehicle.gear
        findViewById<TextView>(R.id.campModeGear).text = when {
            gear?.confirmedPark == true -> getString(
                if (showPreview) R.string.camp_mode_preview_park else R.string.camp_mode_gear_park,
            )
            else -> listOfNotNull(gear?.gear, gear?.autoMode).filter { it in 2..6 }.distinct()
                .singleOrNull()?.let { listOf("R", "N", "D", "S", "M")[it - 2] }
                ?: getString(R.string.camp_mode_reading_unknown)
        }
        findViewById<TextView>(R.id.campModeSoc).text =
            vehicle.soc?.let(::percent) ?: getString(R.string.camp_mode_reading_unknown)
    }

    internal fun renderControls(
        state: CampModeSession.State,
        parkConfirmed: Boolean,
        soc: Double?,
        cutoff: Int,
    ) {
        val lowBattery = batteryAtCutoff(soc, cutoff)
        val adjustable = !state.active && !state.busy
        val waitingForPark = adjustable && !parkConfirmed
        val canStart = adjustable && parkConfirmed && soc != null && !lowBattery
        findViewById<Button>(R.id.campModeCutoffDecrease).isEnabled = adjustable && cutoff > 1
        findViewById<Button>(R.id.campModeCutoffIncrease).isEnabled = adjustable && cutoff < 99
        findViewById<TextView>(R.id.campModeCutoffValue).text = percent(cutoff.toDouble())
        findViewById<TextView>(R.id.campModeHeading).setText(
            when {
                state.busy -> R.string.camp_mode_busy
                state.awaitingWake -> R.string.camp_mode_on
                state.active -> R.string.camp_mode_restore_pending
                else -> R.string.camp_mode_off
            }
        )
        findViewById<Button>(R.id.campModeEnable).apply {
            isEnabled = !state.busy && !state.awaitingWake && (state.active || canStart)
            setText(when {
                state.active && !state.awaitingWake && !state.busy -> R.string.camp_mode_retry_restore
                waitingForPark -> R.string.camp_mode_waiting_for_park
                showPreview -> R.string.camp_mode_preview_start
                else -> R.string.camp_mode_enable
            })
        }
        findViewById<View>(R.id.campModeWindowSafety).visibility =
            if (canStart) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.campModeStopWindows).visibility =
            if (state.busy && !state.restoring && state.currentStep != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.campModeStatus).apply {
            val message = when {
                state.busy -> R.string.camp_mode_working
                state.awaitingWake -> R.string.camp_mode_waiting
                state.active -> R.string.camp_mode_session
                waitingForPark -> null
                soc == null -> R.string.camp_mode_soc_unavailable
                lowBattery -> R.string.camp_mode_soc_at_cutoff
                else -> null
            }
            visibility = if (message == null) View.GONE else View.VISIBLE
            text = message?.let { getString(it) }.orEmpty()
        }
        if (state.busy && state.restoring) {
            reminderDialogPresented = false
            headlightReminderDialog?.dismiss()
        }
        if (!state.remindHeadlights) reminderDialogPresented = false
        if (state.remindHeadlights && !state.busy && !reminderDialogPresented &&
            (!state.active || state.failedSteps.isNotEmpty() || state.sessionError != null)) {
            reminderDialogPresented = true
            val manualActions = CampStep.entries.mapNotNull { step ->
                state.failedSteps[step]?.let { error ->
                    if (error.duringRestoration) error.action else step.recoveryLabel
                }
            }.joinToString("\n") { getString(R.string.camp_mode_manual_action_item, it) }
            val message = buildString {
                append(getString(R.string.camp_mode_headlight_reminder))
                if (manualActions.isNotEmpty()) {
                    append("\n\n")
                    append(getString(R.string.camp_mode_manual_actions, manualActions))
                }
                state.sessionError?.let { append("\n\n").append(it) }
            }
            headlightReminderDialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camp_mode_headlight_reminder_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val preview = previewSession
                    if (preview != null) {
                        lifecycleScope.launch { preview.acknowledgeHeadlightReminder() }
                    } else {
                        controller.acknowledgeHeadlightReminder()
                    }
                }
                .show()
        }
    }

    internal fun renderChecklist(state: CampModeSession.State) {
        val steps = listOf(
            Triple(CampStep.DIKEY, R.id.campStepDikey, R.string.camp_mode_step_dikey),
            Triple(CampStep.BLUETOOTH, R.id.campStepBluetooth, R.string.camp_mode_step_bluetooth),
            Triple(CampStep.WINDOWS, R.id.campStepWindows, R.string.camp_mode_step_windows),
            Triple(CampStep.CLUSTER, R.id.campStepCluster, R.string.camp_mode_step_cluster),
            Triple(CampStep.MEDIA, R.id.campStepMedia, R.string.camp_mode_step_media),
            Triple(CampStep.CARPLAY, R.id.campStepCarPlay, R.string.camp_mode_step_carplay),
            Triple(CampStep.CLIMATE, R.id.campStepClimate, R.string.camp_mode_step_climate),
            Triple(CampStep.DRLS, R.id.campStepDrls, R.string.camp_mode_step_drls),
            Triple(CampStep.HUD, R.id.campStepHud, R.string.camp_mode_step_hud),
            Triple(CampStep.SCREEN, R.id.campStepScreen, R.string.camp_mode_step_screen),
        )
        for ((step, id, label) in steps) {
            val checkbox = findViewById<CheckBox>(id)
            checkbox.isActivated = step in state.failedSteps
            checkbox.isChecked = step in state.completedSteps && !checkbox.isActivated
            checkbox.text = if (state.currentStep == step &&
                (state.restoring || step !in state.completedSteps)) {
                getString(if (state.restoring) R.string.camp_mode_step_restoring else R.string.camp_mode_step_running,
                    getString(label))
            } else getString(label)
            if (step == state.currentStep && step != previousStep) {
                checkbox.post {
                    checkbox.requestRectangleOnScreen(Rect(0, 0, checkbox.width, checkbox.height))
                }
            }
        }
        val errors = CampStep.entries.mapNotNull { step ->
            state.failedSteps[step]?.let { error ->
                getString(R.string.camp_mode_action_error, error.action, error.detail)
            }
        } + listOfNotNull(state.sessionError)
        findViewById<TextView>(R.id.campModeErrors).apply {
            text = errors.joinToString("\n\n")
            visibility = if (errors.isEmpty()) View.GONE else View.VISIBLE
        }
        previousStep = state.currentStep
    }

    companion object {
        const val EXTRA_LIVE_CAMP_MODE = "com.sphy.airconcontroller.LIVE_CAMP_MODE"
    }
}
