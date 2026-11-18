package com.sphy.airconcontroller.camp

import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.storage.LedRestoreSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

internal data class CampSavedState(
    val climate: BydAcController.AcSnapshot,
    val mediaVolume: Int,
    val mediaMuted: Boolean,
    val drlOn: Boolean,
    val dikeyLighting: LedRestoreSnapshot? = null,
    val manageHeadunitBluetooth: Boolean = false,
    val clusterBrightness: Int? = null,
    val hudState: Int? = null,
    val mediaUsesCarMute: Boolean = false,
)

internal data class CampResult(val success: Boolean, val detail: String)

internal enum class CampStep(val reportLabel: String) {
    DIKEY("DiKey lights OFF"),
    CARPLAY("Disconnect CarPlay"),
    BLUETOOTH("Head-unit Bluetooth OFF"),
    CLIMATE("Climate OFF"),
    WINDOWS("Close windows"),
    DRLS("DRLs OFF"),
    CLUSTER("Dim instrument cluster"),
    HUD("HUD OFF"),
    MEDIA("Mute media"),
    SCREEN("Screen OFF");

    companion object {
        fun decodeSaved(names: Set<String>): Set<CampStep> =
            names.filterNot { it == "HEADLIGHTS" }.map { valueOf(it) }.toSet()
    }
}

internal interface CampActions {
    suspend fun save(): CampSavedState
    suspend fun dikeyLightsOff(saved: CampSavedState): CampResult
    suspend fun disconnectCarPlay(): CampResult
    suspend fun headunitBluetoothOff(): CampResult
    suspend fun climateOff(): CampResult
    suspend fun closeWindows(): CampResult
    suspend fun drlOff(): CampResult
    suspend fun dimCluster(): CampResult
    suspend fun hudOff(): CampResult
    suspend fun muteMedia(): CampResult
    suspend fun screenOff(): CampResult
    suspend fun stopWindows(): CampResult
    suspend fun restoreClimate(saved: CampSavedState): CampResult
    suspend fun restoreMedia(saved: CampSavedState): CampResult
    suspend fun restoreDrl(saved: CampSavedState): CampResult
    suspend fun restoreCluster(saved: CampSavedState): CampResult
    suspend fun restoreHud(saved: CampSavedState): CampResult
    suspend fun enableHeadunitBluetooth(saved: CampSavedState): CampResult
    suspend fun enableDikeyLights(saved: CampSavedState): CampResult
    suspend fun persist(saved: CampSavedState?, report: String, completedSteps: Set<CampStep>, remindHeadlights: Boolean)
}

/** One-shot sequence: no periodic enforcement or automatic re-entry after a process restart. */
internal class CampModeSession(
    private val actions: CampActions,
    saved: CampSavedState? = null,
    report: String = "",
    completedSteps: Set<CampStep> = emptySet(),
    remindHeadlights: Boolean = false,
) {
    data class State(
        val active: Boolean,
        val busy: Boolean,
        val report: String,
        val completedSteps: Set<CampStep> = emptySet(),
        val currentStep: CampStep? = null,
        val awaitingWake: Boolean = false,
        val screenOffStartedAtMillis: Long = 0,
        val remindHeadlights: Boolean = false,
    )
    private var saved = saved
    private var stopping = false
    private var restoreAfterEntry = false
    private val mutableState = MutableStateFlow(State(saved != null, false, report,
        if (saved != null) completedSteps else emptySet(),
        awaitingWake = saved != null && CampStep.SCREEN in completedSteps,
        screenOffStartedAtMillis = System.currentTimeMillis(), remindHeadlights = remindHeadlights))
    val state = mutableState.asStateFlow()

    suspend fun enter() {
        if (state.value.busy || saved != null) return
        stopping = false
        restoreAfterEntry = false
        mutableState.value = State(false, true, "", remindHeadlights = state.value.remindHeadlights)
        try {
            val original = actions.save()
            saved = original
            // Durably save restoration data before the very first write to the car.
            actions.persist(saved, "", emptySet(), false)
            mutableState.value = State(true, true, "")
            val steps: List<Pair<CampStep, suspend () -> CampResult>> = listOf(
                CampStep.DIKEY to { actions.dikeyLightsOff(original) },
                CampStep.CARPLAY to { actions.disconnectCarPlay() },
                CampStep.BLUETOOTH to { actions.headunitBluetoothOff() },
                CampStep.CLIMATE to { actions.climateOff() },
                CampStep.WINDOWS to { actions.closeWindows() },
                CampStep.DRLS to { actions.drlOff() },
                CampStep.CLUSTER to { actions.dimCluster() },
                CampStep.HUD to { actions.hudOff() },
                CampStep.MEDIA to { actions.muteMedia() },
                CampStep.SCREEN to { actions.screenOff() },
            )
            for ((step, run) in steps) {
                if (state.value.completedSteps.isNotEmpty()) delay(1000)
                if (stopping) {
                    record("Entry stopped. Use Retry restoring settings to restore the saved settings.")
                    return
                }
                mutableState.value = state.value.copy(currentStep = step)
                val label = step.reportLabel
                record("$label: starting")
                if (stopping) {
                    record("Entry stopped. Use Retry restoring settings to restore the saved settings.")
                    return
                }
                // Arm before the shortcut: a wake event can arrive before its command returns.
                if (step == CampStep.SCREEN) {
                    mutableState.value = state.value.copy(awaitingWake = true,
                        screenOffStartedAtMillis = System.currentTimeMillis())
                }
                val result = run()
                if (step == CampStep.SCREEN && !result.success) {
                    mutableState.value = state.value.copy(awaitingWake = false)
                }
                if (result.success) {
                    mutableState.value = state.value.copy(completedSteps = state.value.completedSteps + step)
                }
                record("$label: ${if (result.success) "OK" else "NOT CONFIRMED"} - ${result.detail}")
                if (!result.success) {
                    record("Entry incomplete. Use Retry restoring settings to restore the saved settings.")
                    return
                }
            }
            if (!restoreAfterEntry) {
                record("Camp Mode active. Touch the screen to wake it and automatically restore saved settings.")
            }
        } catch (e: IOException) {
            failure("Entry failed", e)
        } catch (e: SecurityException) {
            failure("Entry failed", e)
        } catch (e: IllegalStateException) {
            if (e is CancellationException) throw e
            failure("Entry failed", e)
        } finally {
            mutableState.value = state.value.copy(active = saved != null, busy = false, currentStep = null,
                awaitingWake = state.value.awaitingWake && CampStep.SCREEN in state.value.completedSteps)
            if (restoreAfterEntry) {
                restoreAfterEntry = false
                exit()
            }
        }
    }

    suspend fun onScreenWake() {
        if (!state.value.awaitingWake) return
        mutableState.value = state.value.copy(awaitingWake = false)
        if (state.value.busy) restoreAfterEntry = true else exit()
    }

    suspend fun exit() {
        val original = saved ?: return
        if (state.value.busy) return
        mutableState.value = state.value.copy(busy = true, awaitingWake = false, remindHeadlights = true)
        try {
            record("Exiting Camp Mode. Turn the headlights back on and verify normal lighting before driving.")
            var restored = true
            val steps: List<Pair<String, suspend () -> CampResult>> = listOf(
                "Restore instrument cluster" to { actions.restoreCluster(original) },
                "Restore HUD" to { actions.restoreHud(original) },
                "Enable head-unit Bluetooth" to { actions.enableHeadunitBluetooth(original) },
                "Enable DiKey lights" to { actions.enableDikeyLights(original) },
                "Restore media" to { actions.restoreMedia(original) },
                "Restore DRLs" to { actions.restoreDrl(original) },
                "Leave climate OFF" to { actions.restoreClimate(original) },
            )
            for ((label, run) in steps) {
                val result = try {
                    run()
                } catch (e: IOException) {
                    CampResult(false, "${e.javaClass.simpleName}: ${e.message}")
                } catch (e: SecurityException) {
                    CampResult(false, "${e.javaClass.simpleName}: ${e.message}")
                } catch (e: IllegalStateException) {
                    if (e is CancellationException) throw e
                    CampResult(false, "${e.javaClass.simpleName}: ${e.message}")
                }
                record("$label: ${if (result.success) "OK" else "FAILED"} - ${result.detail}")
                restored = restored && result.success
            }
            if (restored) {
                val report = state.value.report + "\nCamp Mode disabled. Windows remain closed; climate remains OFF."
                saved = null
                actions.persist(null, report, emptySet(), state.value.remindHeadlights)
                mutableState.value = state.value.copy(active = false, report = report, completedSteps = emptySet())
            } else {
                record("Restoration incomplete. Saved settings retained; use Retry restoring settings.")
            }
        } catch (e: IOException) {
            saved = original
            failure("Restoration failed", e)
        } catch (e: SecurityException) {
            saved = original
            failure("Restoration failed", e)
        } catch (e: IllegalStateException) {
            if (e is CancellationException) throw e
            saved = original
            failure("Restoration failed", e)
        } finally {
            mutableState.value = state.value.copy(active = saved != null, busy = false)
        }
    }

    suspend fun stopWindows() {
        stopping = true
        try {
            val result = actions.stopWindows()
            record("STOP WINDOWS: ${if (result.success) "sent" else "FAILED"} - ${result.detail}")
        } catch (e: IOException) {
            failure("STOP WINDOWS failed", e)
        } catch (e: SecurityException) {
            failure("STOP WINDOWS failed", e)
        } catch (e: IllegalStateException) {
            if (e is CancellationException) throw e
            failure("STOP WINDOWS failed", e)
        }
    }

    private suspend fun record(line: String) {
        val report = (state.value.report + "\n" + line).trimStart()
        mutableState.value = state.value.copy(report = report)
        actions.persist(saved, report, state.value.completedSteps, state.value.remindHeadlights)
    }

    private suspend fun failure(label: String, e: Exception) {
        // Even a persistence error must remain visible; never disguise it as a completed step.
        mutableState.value = state.value.copy(report = state.value.report + "\n$label: ${e.javaClass.simpleName}: ${e.message}")
        try {
            actions.persist(saved, state.value.report, state.value.completedSteps, state.value.remindHeadlights)
        } catch (saveError: IOException) {
            mutableState.value = state.value.copy(report = state.value.report + "\nCould not save report: ${saveError.message}")
        }
    }
}
