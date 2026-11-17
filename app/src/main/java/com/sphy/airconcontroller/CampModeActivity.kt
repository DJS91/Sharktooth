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
import kotlinx.coroutines.launch

class CampModeActivity : HomeActivity() {
    private var previousStep: CampStep? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camp_mode)
        val controller = CampModeController.get(this)
        val enable = findViewById<Button>(R.id.campModeEnable)
        val status = findViewById<TextView>(R.id.campModeStatus)
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
                controller.state.collect { state ->
                    enable.isEnabled = !state.busy && !state.awaitingWake
                    enable.setText(if (state.active && !state.awaitingWake && !state.busy) {
                        R.string.camp_mode_retry_restore
                    } else R.string.camp_mode_enable)
                    status.setText(when {
                        state.busy -> R.string.camp_mode_working
                        state.awaitingWake -> R.string.camp_mode_waiting
                        state.active -> R.string.camp_mode_session
                        else -> R.string.camp_mode_idle
                    })
                    renderChecklist(state)
                    report.text = state.report
                    report.visibility = if ((state.busy && state.currentStep != null) || state.report.isBlank()) {
                        View.GONE
                    } else View.VISIBLE
                }
            }
        }
    }

    internal fun renderChecklist(state: CampModeSession.State) {
        val steps = listOf(
            Triple(CampStep.CLIMATE, R.id.campStepClimate, R.string.camp_mode_step_climate),
            Triple(CampStep.WINDOWS, R.id.campStepWindows, R.string.camp_mode_step_windows),
            Triple(CampStep.HEADLIGHTS, R.id.campStepHeadlights, R.string.camp_mode_step_headlights),
            Triple(CampStep.DRLS, R.id.campStepDrls, R.string.camp_mode_step_drls),
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
