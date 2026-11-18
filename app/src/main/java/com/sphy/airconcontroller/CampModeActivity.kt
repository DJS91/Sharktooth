package com.sphy.airconcontroller

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sphy.airconcontroller.camp.CampModeController
import com.sphy.airconcontroller.camp.CampModeSession
import com.sphy.airconcontroller.camp.CampStep
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CampModeActivity : HomeActivity() {
    private var previousStep: CampStep? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camp_mode)
        val controller = CampModeController.get(this)
        val enable = findViewById<Button>(R.id.campModeEnable)
        val report = findViewById<TextView>(R.id.campModeReport)
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(controller.state, controller.parkConfirmed) { state, park -> state to park }
                    .collect { (state, park) ->
                        renderControls(state, park)
                        renderChecklist(state)
                        report.text = state.report
                        report.visibility = if ((state.busy && state.currentStep != null) || state.report.isBlank()) {
                            View.GONE
                        } else View.VISIBLE
                    }
            }
        }
    }

    internal fun renderControls(state: CampModeSession.State, parkConfirmed: Boolean) {
        findViewById<Button>(R.id.campModeEnable).apply {
            visibility = if (state.active || parkConfirmed) View.VISIBLE else View.GONE
            isEnabled = !state.busy && !state.awaitingWake && (state.active || parkConfirmed)
            setText(if (state.active && !state.awaitingWake && !state.busy) {
                R.string.camp_mode_retry_restore
            } else R.string.camp_mode_enable)
        }
        findViewById<TextView>(R.id.campModeStatus).setText(when {
            state.busy -> R.string.camp_mode_working
            state.awaitingWake -> R.string.camp_mode_waiting
            state.active -> R.string.camp_mode_session
            !parkConfirmed -> R.string.camp_mode_park_required
            else -> R.string.camp_mode_idle
        })
        findViewById<TextView>(R.id.campModeHeadlightReminder).apply {
            if (state.remindHeadlights) {
                if (visibility != View.VISIBLE) {
                    visibility = View.VISIBLE
                    post { requestRectangleOnScreen(Rect(0, 0, width, height)) }
                }
            } else visibility = View.GONE
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
