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
internal data class CampActionError(val action: String, val detail: String, val duringRestoration: Boolean = false)

internal fun batteryAtCutoff(soc: Double?, cutoff: Int): Boolean =
    soc != null && soc in 0.0..cutoff.toDouble()

internal enum class CampStep(val reportLabel: String, val recoveryLabel: String) {
    DIKEY("DiKey lights OFF", "Enable DiKey lights"),
    BLUETOOTH("Head-unit Bluetooth OFF", "Enable head-unit Bluetooth"),
    WINDOWS("Close windows", "Check that all windows are closed"),
    CLUSTER("Dim instrument cluster", "Restore instrument cluster"),
    MEDIA("Mute media", "Restore media"),
    CARPLAY("Disconnect CarPlay", "Reconnect your phone if needed"),
    CLIMATE("Climate OFF", "Leave climate OFF"),
    DRLS("DRLs OFF", "Restore DRLs"),
    HUD("HUD OFF", "Restore HUD"),
    SCREEN("Screen OFF", "Wake the centre screen");

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
    suspend fun persist(
        saved: CampSavedState?, report: String, completedSteps: Set<CampStep>, remindHeadlights: Boolean,
        failedSteps: Map<CampStep, CampActionError>, sessionError: String?,
    )
}

/** One-shot sequence: no periodic enforcement or automatic re-entry after a process restart. */
internal class CampModeSession(
    private val actions: CampActions,
    saved: CampSavedState? = null,
    report: String = "",
    completedSteps: Set<CampStep> = emptySet(),
    remindHeadlights: Boolean = false,
    failedSteps: Map<CampStep, CampActionError> = emptyMap(),
    sessionError: String? = null,
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
        val failedSteps: Map<CampStep, CampActionError> = emptyMap(),
        val restoring: Boolean = false,
        val sessionError: String? = null,
    )
    private var saved = saved
    private var stopping = false
    private var restoreAfterEntry = false
    private var entryNeedsRollback = false
    private val mutableState = MutableStateFlow(State(saved != null, false, report,
        if (saved != null) completedSteps else emptySet(),
        awaitingWake = saved != null && CampStep.SCREEN in completedSteps,
        screenOffStartedAtMillis = System.currentTimeMillis(), remindHeadlights = remindHeadlights,
        failedSteps = failedSteps, sessionError = sessionError))
    val state = mutableState.asStateFlow()

    suspend fun enter() {
        if (state.value.busy || saved != null) return
        stopping = false
        restoreAfterEntry = false
        entryNeedsRollback = false
        mutableState.value = State(false, true, "", remindHeadlights = state.value.remindHeadlights)
        try {
            val original = actions.save()
            saved = original
            // Durably save restoration data before the very first write to the car.
            actions.persist(saved, state.value.report, emptySet(), false, emptyMap(), null)
            mutableState.value = State(true, true, state.value.report)
            val steps: List<Pair<CampStep, suspend () -> CampResult>> = listOf(
                CampStep.DIKEY to { actions.dikeyLightsOff(original) },
                CampStep.BLUETOOTH to { actions.headunitBluetoothOff() },
                CampStep.WINDOWS to { actions.closeWindows() },
                CampStep.CLUSTER to { actions.dimCluster() },
                CampStep.MEDIA to { actions.muteMedia() },
                CampStep.CARPLAY to { actions.disconnectCarPlay() },
                CampStep.CLIMATE to { actions.climateOff() },
                CampStep.DRLS to { actions.drlOff() },
                CampStep.HUD to { actions.hudOff() },
                CampStep.SCREEN to { actions.screenOff() },
            )
            for ((step, run) in steps) {
                if (state.value.completedSteps.isNotEmpty()) delay(500)
                if (stopping) {
                    record(entryStoppedMessage())
                    return
                }
                mutableState.value = state.value.copy(currentStep = step)
                val label = step.reportLabel
                record("$label: starting")
                if (stopping) {
                    record(entryStoppedMessage())
                    return
                }
                // Arm before the shortcut: a wake event can arrive before its command returns.
                if (step == CampStep.SCREEN) {
                    mutableState.value = state.value.copy(awaitingWake = true,
                        screenOffStartedAtMillis = System.currentTimeMillis())
                }
                val result = attempt(run)
                if (step == CampStep.SCREEN && !result.success) {
                    mutableState.value = state.value.copy(awaitingWake = false)
                }
                if (result.success) {
                    mutableState.value = state.value.copy(completedSteps = state.value.completedSteps + step)
                } else {
                    entryNeedsRollback = true
                    restoreAfterEntry = true
                    mutableState.value = state.value.copy(
                        failedSteps = state.value.failedSteps + (step to CampActionError(label, result.detail)),
                    )
                }
                record("$label: ${if (result.success) "OK" else "NOT CONFIRMED"} - ${result.detail}")
                if (!result.success) {
                    record("Entry incomplete. Automatically restoring settings changed during entry.")
                    return
                }
            }
            if (!restoreAfterEntry) {
                record("Camp Mode active. Touch the screen to wake it and automatically restore saved settings.")
            }
        } catch (e: IOException) {
            entryNeedsRollback = true
            restoreAfterEntry = saved != null
            failure("Entry failed", e)
        } catch (e: SecurityException) {
            entryNeedsRollback = true
            restoreAfterEntry = saved != null
            failure("Entry failed", e)
        } catch (e: IllegalStateException) {
            if (e is CancellationException) throw e
            entryNeedsRollback = true
            restoreAfterEntry = saved != null
            failure("Entry failed", e)
        } finally {
            mutableState.value = state.value.copy(active = saved != null, busy = false, currentStep = null,
                awaitingWake = state.value.awaitingWake && CampStep.SCREEN in state.value.completedSteps)
            if (restoreAfterEntry) {
                restoreAfterEntry = false
                exit(if (entryNeedsRollback) state.value.completedSteps + state.value.failedSteps.keys else null)
            }
        }
    }

    suspend fun onScreenWake() {
        if (!state.value.awaitingWake) return
        mutableState.value = state.value.copy(awaitingWake = false)
        if (state.value.busy) restoreAfterEntry = true else exit()
    }

    suspend fun endForSafety(reason: String) {
        if (state.value.restoring || (saved == null && !state.value.busy)) return
        mutableState.value = state.value.copy(report = (state.value.report + "\n" + reason).trimStart())
        if (state.value.busy) {
            stopping = true
            entryNeedsRollback = true
            restoreAfterEntry = true
        } else {
            exit()
        }
    }

    private fun entryStoppedMessage(): String = "Entry stopped. Automatically restoring saved settings."

    suspend fun checkBatteryCutoff(soc: Double?, cutoff: Int) {
        if (state.value.awaitingWake && !state.value.busy && batteryAtCutoff(soc, cutoff)) exit()
    }

    suspend fun acknowledgeHeadlightReminder() {
        val current = state.value
        if (!current.remindHeadlights) return
        actions.persist(saved, current.report, current.completedSteps, false, current.failedSteps, current.sessionError)
        mutableState.value = current.copy(remindHeadlights = false)
    }

    suspend fun exit(stepsToRestore: Set<CampStep>? = null) {
        val original = saved ?: return
        if (state.value.busy) return
        mutableState.value = state.value.copy(
            busy = true, restoring = true, awaitingWake = false, remindHeadlights = true,
            sessionError = if (stepsToRestore == null) null else state.value.sessionError,
            // These are not reversed: the screen is awake, windows stay closed and CarPlay stays disconnected.
            completedSteps = state.value.completedSteps - setOf(CampStep.SCREEN, CampStep.WINDOWS, CampStep.CARPLAY),
        )
        try {
            record("Exiting Camp Mode. Turn the headlights back on and verify normal lighting before driving.")
            val steps: List<Pair<CampStep, suspend () -> CampResult>> = listOf(
                CampStep.CLUSTER to { actions.restoreCluster(original) },
                CampStep.HUD to { actions.restoreHud(original) },
                CampStep.BLUETOOTH to { actions.enableHeadunitBluetooth(original) },
                CampStep.DIKEY to { actions.enableDikeyLights(original) },
                CampStep.MEDIA to { actions.restoreMedia(original) },
                CampStep.DRLS to { actions.restoreDrl(original) },
                CampStep.CLIMATE to { actions.restoreClimate(original) },
            )
            for ((step, run) in steps) {
                if (stepsToRestore != null && step !in stepsToRestore) continue
                val label = step.recoveryLabel
                mutableState.value = state.value.copy(currentStep = step)
                val result = attempt(run)
                mutableState.value = if (result.success) {
                    state.value.copy(
                        completedSteps = state.value.completedSteps - step,
                        failedSteps = if (state.value.failedSteps[step]?.duringRestoration == false) {
                            state.value.failedSteps
                        } else state.value.failedSteps - step,
                    )
                } else {
                    state.value.copy(
                        failedSteps = state.value.failedSteps + (step to CampActionError(label, result.detail, true)),
                    )
                }
                record("$label: ${if (result.success) "OK" else "FAILED"} - ${result.detail}")
            }
            val report = state.value.report + "\nCamp Mode disabled. Windows remain closed; climate remains OFF." +
                if (state.value.failedSteps.isNotEmpty()) "\nPerform failed recovery actions manually." else ""
            saved = null
            mutableState.value = state.value.copy(active = false, report = report, completedSteps = emptySet())
            persistState()
        } catch (e: IOException) {
            saved = null
            exitPersistenceFailure(e)
        } catch (e: SecurityException) {
            saved = null
            exitPersistenceFailure(e)
        } catch (e: IllegalStateException) {
            if (e is CancellationException) throw e
            saved = null
            exitPersistenceFailure(e)
        } finally {
            mutableState.value = state.value.copy(active = saved != null, busy = false, restoring = false, currentStep = null)
        }
    }

    suspend fun stopWindows() {
        stopping = true
        if (state.value.busy && !state.value.restoring) {
            entryNeedsRollback = true
            restoreAfterEntry = true
        }
        try {
            val result = attempt { actions.stopWindows() }
            if (!result.success) {
                mutableState.value = state.value.copy(
                    failedSteps = state.value.failedSteps +
                        (CampStep.WINDOWS to CampActionError("Stop windows", result.detail)),
                )
            }
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
        if (!state.value.restoring) {
            persistState()
            return
        }
        try {
            persistState()
        } catch (e: IOException) {
            recordPersistenceError(e)
        } catch (e: SecurityException) {
            recordPersistenceError(e)
        } catch (e: IllegalStateException) {
            if (e is CancellationException) throw e
            recordPersistenceError(e)
        }
    }

    private suspend fun attempt(run: suspend () -> CampResult): CampResult = try {
        run()
    } catch (e: IOException) {
        CampResult(false, "${e.javaClass.simpleName}: ${e.message}")
    } catch (e: SecurityException) {
        CampResult(false, "${e.javaClass.simpleName}: ${e.message}")
    } catch (e: IllegalStateException) {
        if (e is CancellationException) throw e
        CampResult(false, "${e.javaClass.simpleName}: ${e.message}")
    }

    private suspend fun persistState() {
        val current = state.value
        actions.persist(saved, current.report, current.completedSteps, current.remindHeadlights,
            current.failedSteps, current.sessionError)
    }

    private fun recordPersistenceError(e: Exception) {
        val message = "Could not save restoration progress: ${e.javaClass.simpleName}: ${e.message}"
        mutableState.value = state.value.copy(sessionError = message, report = state.value.report + "\n$message")
    }

    private fun exitPersistenceFailure(e: Exception) {
        val message = "Could not save Camp Mode exit: ${e.javaClass.simpleName}: ${e.message}. " +
            "The saved session may return after an app restart; verify restoration manually."
        mutableState.value = state.value.copy(active = false, completedSteps = emptySet(),
            sessionError = message, report = state.value.report + "\n" + message)
    }

    private suspend fun failure(label: String, e: Exception) {
        // Even a persistence error must remain visible; never disguise it as a completed step.
        val message = "$label: ${e.javaClass.simpleName}: ${e.message}"
        mutableState.value = state.value.copy(report = state.value.report + "\n$message", sessionError = message)
        try {
            persistState()
        } catch (saveError: IOException) {
            recordPersistenceError(saveError)
        } catch (saveError: SecurityException) {
            recordPersistenceError(saveError)
        } catch (saveError: IllegalStateException) {
            if (saveError is CancellationException) throw saveError
            recordPersistenceError(saveError)
        }
    }
}
