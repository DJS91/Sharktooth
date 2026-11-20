package com.sphy.airconcontroller.camp

import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.dikey.LedMode
import com.sphy.airconcontroller.storage.LedBarState
import com.sphy.airconcontroller.storage.LedRestoreSnapshot
import com.sphy.airconcontroller.storage.LedRgb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CampModeSessionTest {
    private val saved = CampSavedState(
        BydAcController.AcSnapshot(true, true, null, true, 22, 23, null, 3,
            1, 1, 2, 1, false, false, false, false, true),
        7, false, true,
        LedRestoreSnapshot((1..3).associateWith { LedBarState(LedMode.ON.code, LedRgb(4, 8, 16)) }, LedRgb(2, 4, 8)),
        true, 7, 1, mediaUsesCarMute = true,
    )

    private inner class Actions : CampActions {
        val calls = mutableListOf<String>()
        var stored: CampSavedState? = null
        var fail: String? = null
        var restoreFailure: String? = null
        var failPersist = false
        var remindHeadlights = false
        var connectedAtEntry = true
        var onClose: (suspend () -> Unit)? = null
        var onScreen: (suspend () -> Unit)? = null
        var onPersist: (suspend (String) -> Unit)? = null
        var onAction: ((String) -> Unit)? = null
        var completedSteps: Set<CampStep> = emptySet()
        var failedSteps: Map<CampStep, CampActionError> = emptyMap()
        private fun result(name: String): CampResult {
            calls += name
            onAction?.invoke(name)
            return CampResult(fail != name && restoreFailure != name, name)
        }
        override suspend fun save(): CampSavedState {
            calls += "save"
            check(fail != "save") { "Park could not be confirmed." }
            return saved.copy(dikeyLighting = if (connectedAtEntry) saved.dikeyLighting else null)
        }
        override suspend fun dikeyLightsOff(saved: CampSavedState) =
            result(if (saved.dikeyLighting == null) "dikey-skipped" else "dikey")
        override suspend fun headunitBluetoothOff() = result("bluetooth")
        override suspend fun disconnectCarPlay() = result("carplay")
        override suspend fun enableHeadunitBluetooth(saved: CampSavedState) = result("enable-bluetooth")
        override suspend fun enableDikeyLights(saved: CampSavedState) =
            result(if (saved.dikeyLighting == null) "enable-dikey-skipped" else "enable-dikey")
        override suspend fun climateOff() = result("climate")
        override suspend fun closeWindows(): CampResult { onClose?.invoke(); return result("windows") }
        override suspend fun drlOff() = result("drl")
        override suspend fun dimCluster() = result("cluster")
        override suspend fun hudOff() = result("hud")
        override suspend fun muteMedia() = result("mute")
        override suspend fun screenOff(): CampResult { onScreen?.invoke(); return result("screen") }
        override suspend fun stopWindows() = result("stop")
        override suspend fun restoreClimate(saved: CampSavedState): CampResult {
            assertEquals(this@CampModeSessionTest.saved.climate, saved.climate)
            return result("restore-climate")
        }
        override suspend fun restoreMedia(saved: CampSavedState): CampResult {
            assertTrue(saved.mediaUsesCarMute)
            return result("restore-media")
        }
        override suspend fun restoreDrl(saved: CampSavedState) = result("restore-drl")
        override suspend fun restoreCluster(saved: CampSavedState): CampResult {
            assertEquals(7, saved.clusterBrightness)
            return result("restore-cluster")
        }
        override suspend fun restoreHud(saved: CampSavedState): CampResult {
            assertEquals(1, saved.hudState)
            return result("restore-hud")
        }
        override suspend fun persist(
            saved: CampSavedState?, report: String, completedSteps: Set<CampStep>, remindHeadlights: Boolean,
            failedSteps: Map<CampStep, CampActionError>, sessionError: String?,
        ) {
            if (failPersist) throw IOException("Storage unavailable")
            stored = saved
            this.completedSteps = completedSteps
            this.remindHeadlights = remindHeadlights
            this.failedSteps = failedSteps
            calls += "persist"
            onPersist?.invoke(report)
        }
    }

    @Test fun savesBeforeChangingCarAndRunsScreenOffLast() = runBlocking {
        val actions = Actions()
        val session = CampModeSession(actions)
        val actionTimes = mutableListOf<Long>()
        actions.onAction = {
            val index = actionTimes.size
            assertEquals(CampStep.entries.take(index).toSet(), session.state.value.completedSteps)
            assertEquals(CampStep.entries[index], session.state.value.currentStep)
            actionTimes += System.nanoTime()
        }

        @Test fun acknowledgingHeadlightReminderPersistsDismissal() = runBlocking {
            val actions = Actions()
            val session = CampModeSession(actions, remindHeadlights = true)

            session.acknowledgeHeadlightReminder()

            assertFalse(session.state.value.remindHeadlights)
            assertFalse(actions.remindHeadlights)
        }
        session.enter()
        assertEquals(listOf("save", "persist"), actions.calls.take(2))
        assertEquals(listOf("save", "dikey", "bluetooth", "windows", "cluster", "mute", "carplay", "climate", "drl", "hud", "screen"),
            actions.calls.filter { it != "persist" })
        session.enter()
        assertEquals(1, actions.calls.count { it == "save" })
        assertEquals(saved, actions.stored)
        assertTrue(session.state.value.active)
        assertTrue(session.state.value.awaitingWake)
        assertEquals(CampStep.entries.toSet(), session.state.value.completedSteps)
        assertEquals(session.state.value.completedSteps, actions.completedSteps)
        assertNull(session.state.value.currentStep)
        val pauses = actionTimes.zipWithNext { previous, next -> next - previous }
        assertTrue("Each confirmed action must remain visible for at least half a second",
            pauses.all { it >= 500_000_000L })
        assertTrue("The fastest pause should be shorter than the old one-second delay",
            pauses.any { it < 1_000_000_000L })
    }

    @Test fun failedStepStopsEntryAndAutomaticallyUndoesAttemptedActions() = runBlocking {
        val actions = Actions().apply { fail = "drl" }
        val session = CampModeSession(actions)
        session.enter()
        assertFalse(actions.calls.contains("hud"))
        assertFalse(actions.calls.contains("screen"))
        assertTrue(session.state.value.report.contains("NOT CONFIRMED"))
        assertFalse(session.state.value.active)
        assertEquals(CampActionError("DRLs OFF", "drl"), session.state.value.failedSteps[CampStep.DRLS])
        assertEquals(session.state.value.failedSteps, actions.failedSteps)
        assertTrue(actions.calls.containsAll(listOf(
            "restore-cluster", "enable-bluetooth", "enable-dikey", "restore-media", "restore-drl", "restore-climate",
        )))
        assertFalse(actions.calls.contains("restore-hud"))
        assertNull(actions.stored)
        assertTrue(session.state.value.completedSteps.isEmpty())
        assertTrue(actions.completedSteps.isEmpty())
    }

    @Test fun preflightOrPersistenceFailureNeverChangesVehicleSettings() = runBlocking {
        for (failStorage in listOf(false, true)) {
            val actions = Actions().apply {
                if (failStorage) failPersist = true else fail = "save"
            }
            val session = CampModeSession(actions)
            session.enter()
            assertFalse(session.state.value.busy)
            assertFalse(actions.calls.any { it in listOf("dikey", "carplay", "bluetooth", "climate", "windows", "drl", "cluster", "hud", "mute", "screen") })
            assertTrue(session.state.value.report.contains("Entry failed"))
            assertEquals(failStorage, session.state.value.active)
        }
    }

    @Test fun restartRetainsOriginalSettingsAndRetryAttemptsAllRestorations() = runBlocking {
        val actions = Actions().apply { fail = "restore-media" }
        val recovered = CampModeSession(actions, saved, "Previous session")
        recovered.exit()
        assertTrue(recovered.state.value.active)
        assertEquals(saved, actions.stored)
        assertTrue(actions.calls.containsAll(listOf("restore-cluster", "restore-hud", "enable-bluetooth", "enable-dikey",
            "restore-media", "restore-drl", "restore-climate")))
        actions.fail = null
        recovered.exit()
        assertFalse(recovered.state.value.active)
        assertNull(actions.stored)
    }

    @Test fun stopWindowsPreventsRemainingEntrySteps() = runBlocking {
        val actions = Actions()
        val session = CampModeSession(actions)
        actions.onClose = { session.stopWindows() }
        session.enter()
        assertTrue(actions.calls.contains("stop"))
        assertFalse(actions.calls.contains("drl"))
        assertFalse(actions.calls.contains("screen"))
        assertTrue(session.state.value.active)
    }

    @Test fun stopDuringProgressSaveDoesNotStartWindowMovement() = runBlocking {
        val actions = Actions()
        val session = CampModeSession(actions)
        actions.onPersist = { report ->
            if (report.endsWith("Close windows: starting")) session.stopWindows()
        }
        session.enter()
        assertTrue(actions.calls.contains("stop"))
        assertFalse(actions.calls.contains("windows"))
        assertFalse(actions.calls.contains("screen"))
    }

    @Test fun legacyHeadlightProgressIsIgnoredWithoutLosingScreenWakeRestoration() = runBlocking {
        val actions = Actions()
        val steps = CampStep.decodeSaved(setOf("HEADLIGHTS", "MEDIA", "SCREEN"))
        assertEquals(setOf(CampStep.MEDIA, CampStep.SCREEN), steps)
        val recovered = CampModeSession(actions, saved, completedSteps = steps)
        assertTrue(recovered.state.value.awaitingWake)
        recovered.onScreenWake()
        assertFalse(recovered.state.value.active)
        assertEquals(listOf("restore-cluster", "restore-hud", "enable-bluetooth", "enable-dikey",
            "restore-media", "restore-drl", "restore-climate"), actions.calls.filter { it != "persist" })
        assertNull(actions.stored)
        assertTrue(actions.remindHeadlights)
    }

    @Test fun exitReminderSurvivesFailedRestorationRetryAndRestart() = runBlocking {
        val actions = Actions().apply { fail = "restore-media" }
        val session = CampModeSession(actions, saved)
        assertFalse(session.state.value.remindHeadlights)
        session.exit()
        assertTrue(session.state.value.active)
        assertTrue(session.state.value.remindHeadlights)
        assertTrue(actions.remindHeadlights)
        val recovered = CampModeSession(actions, actions.stored, remindHeadlights = actions.remindHeadlights)
        assertTrue(recovered.state.value.remindHeadlights)
        actions.fail = null
        recovered.exit()
        assertFalse(recovered.state.value.active)
        assertTrue(actions.remindHeadlights)
        val idle = CampModeSession(actions, actions.stored, remindHeadlights = actions.remindHeadlights)
        assertFalse(idle.state.value.active)
        assertTrue(idle.state.value.remindHeadlights)
    }

    @Test fun entryFailureKeepsTheReminderAfterAutomaticRollback() = runBlocking {
        val actions = Actions().apply { fail = "save" }
        val session = CampModeSession(actions, remindHeadlights = true)
        session.enter()
        assertTrue(session.state.value.remindHeadlights)
        assertTrue(actions.remindHeadlights)
        actions.fail = "dikey"
        session.enter()
        assertFalse(session.state.value.active)
        assertTrue(session.state.value.remindHeadlights)
        assertTrue(actions.remindHeadlights)
        assertEquals(CampActionError("DiKey lights OFF", "dikey"), session.state.value.failedSteps[CampStep.DIKEY])
    }

    @Test fun stoppingDuringTheOneSecondGapKeepsCompletedChecksAndPreventsNextAction() = runBlocking {
        val actions = Actions()
        val session = CampModeSession(actions)
        val climateDone = CompletableDeferred<Unit>()
        actions.onPersist = { report ->
            if (report.endsWith("Climate OFF: OK - climate")) climateDone.complete(Unit)
        }
        val entry = launch { session.enter() }
        climateDone.await()
        val completed = setOf(CampStep.DIKEY, CampStep.BLUETOOTH, CampStep.WINDOWS, CampStep.CLUSTER,
            CampStep.MEDIA, CampStep.CARPLAY, CampStep.CLIMATE)
        assertEquals(completed, session.state.value.completedSteps)
        session.stopWindows()
        entry.join()
        assertTrue(actions.calls.contains("stop"))
        assertFalse(actions.calls.contains("drl"))
        assertFalse(actions.calls.contains("screen"))
        assertEquals(completed, actions.completedSteps)
        assertTrue(session.state.value.report.contains("Entry stopped"))
    }

    @Test fun restartedSessionKeepsOnlyConfirmedChecksWithoutReapplyingActions() {
        val actions = Actions()
        val completed = setOf(CampStep.CLIMATE, CampStep.WINDOWS)
        val recovered = CampModeSession(actions, saved, "Previous session", completed)
        assertEquals(completed, recovered.state.value.completedSteps)
        assertTrue(actions.calls.isEmpty())
        assertNull(recovered.state.value.currentStep)
        val idle = CampModeSession(actions, completedSteps = completed)
        assertTrue(idle.state.value.completedSteps.isEmpty())
    }

    @Test fun wakingAutomaticallyRestoresAllSavedSettingsOnlyOnce() = runBlocking {
        val actions = Actions()
        val session = CampModeSession(actions, saved, completedSteps = CampStep.entries.toSet())
        assertTrue(session.state.value.awaitingWake)
        session.onScreenWake()
        assertEquals(listOf("restore-cluster", "restore-hud", "enable-bluetooth", "enable-dikey", "restore-media", "restore-drl", "restore-climate"),
            actions.calls.filter { it != "persist" })
        assertFalse(session.state.value.active)
        assertFalse(session.state.value.awaitingWake)
        assertNull(actions.stored)
        session.onScreenWake()
        assertEquals(5, actions.calls.count { it.startsWith("restore-") })
    }

    @Test fun batteryCutoffRestoresAnActiveCampOnceAtOrBelowTheSelectedCharge() = runBlocking {
        for (lowSoc in listOf(10.0, 9.5)) {
            val actions = Actions()
            val session = CampModeSession(actions, saved, completedSteps = CampStep.entries.toSet())
            session.checkBatteryCutoff(null, 10)
            session.checkBatteryCutoff(10.1, 10)
            assertTrue(session.state.value.awaitingWake)
            assertTrue(actions.calls.isEmpty())

            session.checkBatteryCutoff(lowSoc, 10)
            session.checkBatteryCutoff(lowSoc, 10)
            assertFalse(session.state.value.active)
            assertTrue(session.state.value.remindHeadlights)
            assertEquals(1, actions.calls.count { it == "restore-cluster" })
            assertEquals(1, actions.calls.count { it == "restore-climate" })
        }
    }

    @Test fun touchBeforeScreenOffDoesNotInterruptEntryButImmediateWakeIsNotLost() = runBlocking {
        val actions = Actions()
        val session = CampModeSession(actions)
        actions.onClose = {
            session.onScreenWake()
            assertTrue(actions.calls.none { it.startsWith("restore-") })
        }
        actions.onPersist = { report ->
            if (report.endsWith("Screen OFF: starting")) {
                session.onScreenWake()
                assertFalse(session.state.value.awaitingWake)
            }
        }
        actions.onScreen = {
            assertTrue(session.state.value.awaitingWake)
            session.onScreenWake()
            assertTrue("Restoration must wait until the screen command returns",
                actions.calls.none { it.startsWith("restore-") })
        }
        session.enter()
        assertEquals(listOf("save", "dikey", "bluetooth", "windows", "cluster", "mute", "carplay", "climate", "drl", "hud", "screen",
            "restore-cluster", "restore-hud", "enable-bluetooth", "enable-dikey", "restore-media", "restore-drl", "restore-climate"),
            actions.calls.filter { it != "persist" })
        assertFalse(session.state.value.active)
        assertFalse(session.state.value.awaitingWake)
        assertFalse(session.state.value.busy)
    }

    @Test fun failedAutomaticRestorationRetainsSettingsForExplicitRetry() = runBlocking {
        val actions = Actions().apply { fail = "restore-drl" }
        val session = CampModeSession(actions, saved, completedSteps = CampStep.entries.toSet())
        session.onScreenWake()
        assertTrue(session.state.value.active)
        assertFalse(session.state.value.awaitingWake)
        assertEquals(saved, actions.stored)
        assertTrue(session.state.value.report.contains("Restoration incomplete"))
        session.onScreenWake()
        assertEquals("Duplicate wake signals must not repeatedly restore settings", 5,
            actions.calls.count { it.startsWith("restore-") })
        actions.fail = null
        session.exit()
        assertFalse(session.state.value.active)
        assertNull(actions.stored)
    }

    @Test fun rejectedOrThrowingScreenOffAutomaticallyRestoresWithoutWaitingForWake() = runBlocking {
        for (throws in listOf(false, true)) {
            val actions = Actions().apply {
                if (throws) onScreen = { throw IOException("Screen command unavailable") } else fail = "screen"
            }
            val session = CampModeSession(actions)
            session.enter()
            assertFalse(session.state.value.active)
            assertFalse(session.state.value.awaitingWake)
            assertFalse(session.state.value.busy)
            assertFalse(CampStep.SCREEN in session.state.value.completedSteps)
            assertTrue(session.state.value.report.contains(
                if (throws) "Screen command unavailable" else "NOT CONFIRMED",
            ))
            assertNotNull(session.state.value.failedSteps[CampStep.SCREEN])
            assertTrue(actions.calls.contains("restore-climate"))
            assertFalse(session.state.value.active)
        }
    }

    @Test fun newInitialStepsFailWithoutStartingClimateOrBlankingScreen() = runBlocking {
        for (step in listOf("dikey", "carplay", "bluetooth")) {
            val actions = Actions().apply { fail = step }
            val session = CampModeSession(actions)
            session.enter()
            assertFalse(actions.calls.contains("climate"))
            assertFalse(actions.calls.contains("screen"))
            assertFalse(session.state.value.active)
            assertTrue(session.state.value.report.contains("NOT CONFIRMED"))
            assertEquals(if (step == "dikey") listOf("enable-dikey") else listOf("enable-bluetooth", "enable-dikey"),
                actions.calls.filter { it.startsWith("enable-") })
            assertFalse(session.state.value.active)
        }
    }

    @Test fun disconnectedDikeyIsSkippedWithoutSkippingHeadunitBluetooth() = runBlocking {
        val actions = Actions().apply { connectedAtEntry = false }
        val session = CampModeSession(actions)
        session.enter()
        assertTrue(actions.calls.contains("dikey-skipped"))
        assertFalse(actions.calls.contains("dikey"))
        assertTrue(actions.calls.contains("bluetooth"))
        session.onScreenWake()
        assertTrue(actions.calls.contains("enable-dikey-skipped"))
        assertTrue(actions.calls.contains("enable-bluetooth"))
        assertFalse(session.state.value.active)
    }

    @Test fun failedClusterOrHudStepStopsBeforeScreenOffAndKeepsRestorationRetryable() = runBlocking {
        for (step in listOf("cluster", "hud")) {
            val actions = Actions().apply { fail = step; restoreFailure = "restore-$step" }
            val session = CampModeSession(actions)
            session.enter()
            assertEquals(step == "hud", actions.calls.contains("mute"))
            assertFalse(actions.calls.contains("screen"))
            assertEquals(saved, actions.stored)
            assertFalse(CampStep.SCREEN in session.state.value.completedSteps)
            assertTrue(session.state.value.active)
            assertEquals(saved, actions.stored)
            if (step == "hud") assertTrue(actions.calls.contains("restore-climate"))
            actions.fail = null
            actions.restoreFailure = null
            session.exit()
            assertFalse(session.state.value.active)
            assertNull(actions.stored)
        }
    }

    @Test fun restorationUnticksSuccessesAndContinuesAfterReturnedOrThrownErrors() = runBlocking {
        for (throws in listOf(false, true)) {
            val actions = Actions()
            val session = CampModeSession(actions, saved, completedSteps = CampStep.entries.toSet())
            actions.onAction = { name ->
                if (name == "restore-hud") {
                    assertFalse(CampStep.CLUSTER in session.state.value.completedSteps)
                    if (throws) throw IOException("HUD unavailable")
                }
                if (name == "restore-media") {
                    assertFalse(CampStep.BLUETOOTH in session.state.value.completedSteps)
                    assertFalse(CampStep.DIKEY in session.state.value.completedSteps)
                }
            }
            if (!throws) actions.fail = "restore-hud"
            session.exit()
            assertEquals(setOf(CampStep.HUD), session.state.value.completedSteps)
            assertEquals(setOf(CampStep.HUD), session.state.value.failedSteps.keys)
            assertTrue(checkNotNull(session.state.value.failedSteps[CampStep.HUD]).duringRestoration)
            assertTrue(actions.calls.contains("restore-climate"))
            assertTrue(session.state.value.active)
            assertEquals(session.state.value.failedSteps, actions.failedSteps)
            val recovered = CampModeSession(actions, actions.stored,
                completedSteps = actions.completedSteps, failedSteps = actions.failedSteps,
                remindHeadlights = actions.remindHeadlights)
            assertEquals(session.state.value.failedSteps, recovered.state.value.failedSteps)
            actions.fail = null
            actions.onAction = null
            recovered.exit()
            assertFalse(recovered.state.value.active)
            assertTrue(recovered.state.value.completedSteps.isEmpty())
            assertTrue(recovered.state.value.failedSteps.isEmpty())
        }
    }

    @Test fun restorationStillAttemptsEveryActionWhenProgressCannotBeSaved() = runBlocking {
        val actions = Actions().apply { failPersist = true }
        val session = CampModeSession(actions, saved, completedSteps = CampStep.entries.toSet())
        session.exit()
        assertEquals(listOf("restore-cluster", "restore-hud", "enable-bluetooth", "enable-dikey",
            "restore-media", "restore-drl", "restore-climate"), actions.calls.filter { it != "persist" })
        assertTrue(session.state.value.active)
        assertTrue(checkNotNull(session.state.value.sessionError).contains("Storage unavailable"))
    }

    @Test fun safetyExitDuringEntryStopsLaterStepsAndRollsBackWithoutBlankingScreen() = runBlocking {
        val actions = Actions()
        val session = CampModeSession(actions)
        actions.onClose = { session.endForSafety("Vehicle left Park") }
        session.enter()
        assertFalse(actions.calls.contains("cluster"))
        assertFalse(actions.calls.contains("screen"))
        assertEquals(listOf("save", "dikey", "bluetooth", "windows", "enable-bluetooth", "enable-dikey"),
            actions.calls.filter { it != "persist" })
        assertFalse(session.state.value.active)
        assertTrue(session.state.value.remindHeadlights)
        assertTrue(session.state.value.report.contains("Vehicle left Park"))
        assertNull(actions.stored)
    }

    @Test fun safetyExitRestoresARecoveredActiveSession() = runBlocking {
        val actions = Actions()
        val session = CampModeSession(actions, saved, completedSteps = CampStep.entries.toSet())
        session.endForSafety("Vehicle restarted")
        assertFalse(session.state.value.active)
        assertFalse(session.state.value.awaitingWake)
        assertTrue(actions.calls.contains("restore-climate"))
        assertTrue(session.state.value.remindHeadlights)
        assertNull(actions.stored)
    }
}
