package com.sphy.airconcontroller

import android.graphics.Rect
import android.os.Bundle
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
import com.sphy.airconcontroller.camp.CampModeController
import com.sphy.airconcontroller.camp.CampModeSession
import com.sphy.airconcontroller.camp.CampStep
import com.sphy.airconcontroller.camp.batteryAtCutoff
import kotlinx.coroutines.flow.combine
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camp_mode)
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
            gear?.confirmedPark == true -> getString(R.string.camp_mode_gear_park)
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
                else -> R.string.camp_mode_enable
            })
        }
        findViewById<View>(R.id.campModeWindowSafety).visibility =
            if (canStart) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.campModeStopWindows).visibility =
            if (state.busy && state.currentStep != null) View.VISIBLE else View.GONE
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
        if (!state.remindHeadlights) reminderDialogPresented = false
        if (state.remindHeadlights && !state.active && !state.busy && !reminderDialogPresented) {
            reminderDialogPresented = true
            headlightReminderDialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camp_mode_headlight_reminder_title)
                .setMessage(R.string.camp_mode_headlight_reminder)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    controller.acknowledgeHeadlightReminder()
                }
                .show()
        }
    }

    internal fun renderChecklist(state: CampModeSession.State) {
        val steps = listOf(
            Triple(CampStep.DIKEY, R.id.campStepDikey, R.string.camp_mode_step_dikey),
            Triple(CampStep.CARPLAY, R.id.campStepCarPlay, R.string.camp_mode_step_carplay),
            Triple(CampStep.BLUETOOTH, R.id.campStepBluetooth, R.string.camp_mode_step_bluetooth),
            Triple(CampStep.CLIMATE, R.id.campStepClimate, R.string.camp_mode_step_climate),
            Triple(CampStep.WINDOWS, R.id.campStepWindows, R.string.camp_mode_step_windows),
            Triple(CampStep.DRLS, R.id.campStepDrls, R.string.camp_mode_step_drls),
            Triple(CampStep.CLUSTER, R.id.campStepCluster, R.string.camp_mode_step_cluster),
            Triple(CampStep.HUD, R.id.campStepHud, R.string.camp_mode_step_hud),
            Triple(CampStep.MEDIA, R.id.campStepMedia, R.string.camp_mode_step_media),
            Triple(CampStep.SCREEN, R.id.campStepScreen, R.string.camp_mode_step_screen),
        )
        for ((step, id, label) in steps) {
            val checkbox = findViewById<CheckBox>(id)
            checkbox.isChecked = step in state.completedSteps
            checkbox.text = if (state.currentStep == step && step !in state.completedSteps) {
                getString(R.string.camp_mode_step_running, getString(label))
            } else getString(label)
            if (step == state.currentStep && step != previousStep) {
                checkbox.post {
                    checkbox.requestRectangleOnScreen(Rect(0, 0, checkbox.width, checkbox.height))
                }
            }
        }
        previousStep = state.currentStep
    }
}
