package com.sphy.airconcontroller

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sphy.airconcontroller.camp.CampModeController
import com.sphy.airconcontroller.camp.CampModeSession
import com.sphy.airconcontroller.camp.CampStep
import com.sphy.airconcontroller.camp.batteryAtCutoff
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.NumberFormat

class CampModeActivity : HomeActivity() {
    private var previousStep: CampStep? = null
    private var reminderDialogPresented = false
    internal var headlightReminderDialog: AlertDialog? = null
        private set
    private lateinit var controller: CampModeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camp_mode)
        controller = CampModeController.get(this)
        val enable = findViewById<Button>(R.id.campModeEnable)
        enable.setOnClickListener {
            if (controller.state.value.active) {
                controller.exit()
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.camp_mode_enable)
                    .setMessage(R.string.camp_mode_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.camp_mode_enable) { _, _ -> controller.enter() }
                    .show()
            }
        }
        findViewById<Button>(R.id.campModeStopWindows).setOnClickListener { controller.stopWindows() }
        findViewById<Button>(R.id.campModeCutoffDecrease).setOnClickListener { controller.adjustBatteryCutoff(-1) }
        findViewById<Button>(R.id.campModeCutoffIncrease).setOnClickListener { controller.adjustBatteryCutoff(1) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
            isEnabled = !state.busy && !state.awaitingWake &&
                (state.active || (parkConfirmed && soc != null && !lowBattery))
            setText(when {
                state.active && !state.awaitingWake && !state.busy -> R.string.camp_mode_retry_restore
                waitingForPark -> R.string.camp_mode_waiting_for_park
                else -> R.string.camp_mode_enable
            })
        }
        findViewById<Button>(R.id.campModeStopWindows).visibility =
            if (state.busy && state.currentStep != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.campModeStatus).apply {
            visibility = if (waitingForPark) View.GONE else View.VISIBLE
            val message = when {
                state.busy -> R.string.camp_mode_working
                state.awaitingWake -> R.string.camp_mode_waiting
                state.active -> R.string.camp_mode_session
                waitingForPark -> null
                soc == null -> R.string.camp_mode_soc_unavailable
                lowBattery -> R.string.camp_mode_soc_at_cutoff
                else -> R.string.camp_mode_idle
            }
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
