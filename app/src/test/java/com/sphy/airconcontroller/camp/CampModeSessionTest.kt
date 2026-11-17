package com.sphy.airconcontroller.camp

import com.sphy.airconcontroller.byd.BydAcController
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
        7, false, true, 2,
    )

    private inner class Actions : CampActions {
        val calls = mutableListOf<String>()
        var stored: CampSavedState? = null
        var fail: String? = null
        var failPersist = false
        var onClose: (suspend () -> Unit)? = null
        var onScreen: (suspend () -> Unit)? = null
        var onPersist: (suspend (String) -> Unit)? = null
        var onAction: ((String) -> Unit)? = null
        var completedSteps: Set<CampStep> = emptySet()
        private fun result(name: String): CampResult {
            calls += name
            onAction?.invoke(name)
            return CampResult(fail != name, name)
        }
        override suspend fun save(): CampSavedState {
            calls += "save"
            check(fail != "save") { "Park could not be confirmed." }
            return saved
        }
        override suspend fun climateOff() = result("climate")
        override suspend fun closeWindows(): CampResult { onClose?.invoke(); return result("windows") }
        override suspend fun headlightsOff() = result("headlights")
        override suspend fun drlOff() = result("drl")
        override suspend fun muteMedia() = result("mute")
        override suspend fun screenOff(): CampResult { onScreen?.invoke(); return result("screen") }
        override suspend fun stopWindows() = result("stop")
        override suspend fun restoreClimate(saved: CampSavedState): CampResult {
            assertEquals(this@CampModeSessionTest.saved, saved)
            return result("restore-climate")
        }
        override suspend fun restoreMedia(saved: CampSavedState) = result("restore-media")
        override suspend fun restoreDrl(saved: CampSavedState) = result("restore-drl")
        override suspend fun restoreHeadlights(saved: CampSavedState): CampResult {
            assertEquals(2, saved.headlightMode)
            return result("restore-headlights")
        }
        override suspend fun persist(saved: CampSavedState?, report: String, completedSteps: Set<CampStep>) {
            if (failPersist) throw IOException("Storage unavailable")
            stored = saved
            this.completedSteps = completedSteps
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
        session.enter()
        assertEquals(listOf("save", "persist"), actions.calls.take(2))
        assertEquals(listOf("save", "climate", "windows", "headlights", "drl", "mute", "screen"),
            actions.calls.filter { it != "persist" })
        session.enter()
        assertEquals(1, actions.calls.count { it == "save" })
        assertEquals(saved, actions.stored)
        assertTrue(session.state.value.active)
        assertTrue(session.state.value.awaitingWake)
        assertEquals(CampStep.entries.toSet(), session.state.value.completedSteps)
        assertEquals(session.state.value.completedSteps, actions.completedSteps)
        assertNull(session.state.value.currentStep)
        for ((previous, next) in actionTimes.zipWithNext()) {
            assertTrue("Each confirmed action must remain visible for at least one second",
                next - previous >= 1_000_000_000L)
        }
    }

    @Test fun failedStepStopsEntryWithoutBlankingScreenAndRemainsRestorable() = runBlocking {
        val actions = Actions().apply { fail = "drl" }
        val session = CampModeSession(actions)
        session.enter()
        assertFalse(actions.calls.contains("mute"))
        assertFalse(actions.calls.contains("screen"))
        assertTrue(session.state.value.report.contains("NOT CONFIRMED"))
        assertTrue(session.state.value.active)
        assertEquals(setOf(CampStep.CLIMATE, CampStep.WINDOWS, CampStep.HEADLIGHTS),
            session.state.value.completedSteps)
        session.exit()
        assertFalse(session.state.value.active)
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
            assertFalse(actions.calls.any { it in listOf("climate", "windows", "headlights", "drl", "mute", "screen") })
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
        assertTrue(actions.calls.containsAll(listOf("restore-media", "restore-headlights", "restore-drl", "restore-climate")))
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

    @Test fun failedHeadlightStepPreventsScreenOffAndKeepsRestorationState() = runBlocking {
        val actions = Actions().apply { fail = "headlights" }
        val session = CampModeSession(actions)
        session.enter()
        assertFalse(actions.calls.contains("drl"))
        assertFalse(actions.calls.contains("screen"))
        assertEquals(saved, actions.stored)
        actions.fail = "restore-headlights"
        session.exit()
        assertTrue(session.state.value.active)
        assertEquals(saved, actions.stored)
        assertTrue(actions.calls.contains("restore-climate"))
        actions.fail = null
        session.exit()
        assertFalse(session.state.value.active)
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
        assertEquals(setOf(CampStep.CLIMATE), session.state.value.completedSteps)
        session.stopWindows()
        entry.join()
        assertTrue(actions.calls.contains("stop"))
        assertFalse(actions.calls.contains("windows"))
        assertFalse(actions.calls.contains("screen"))
        assertEquals(setOf(CampStep.CLIMATE), actions.completedSteps)
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
        assertEquals(listOf("restore-media", "restore-headlights", "restore-drl", "restore-climate"),
            actions.calls.filter { it != "persist" })
        assertFalse(session.state.value.active)
        assertFalse(session.state.value.awaitingWake)
        assertNull(actions.stored)
        session.onScreenWake()
        assertEquals(4, actions.calls.count { it.startsWith("restore-") })
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
        assertEquals(listOf("save", "climate", "windows", "headlights", "drl", "mute", "screen",
            "restore-media", "restore-headlights", "restore-drl", "restore-climate"),
            actions.calls.filter { it != "persist" })
        assertFalse(session.state.value.active)
        assertFalse(session.state.value.awaitingWake)
        assertFalse(session.state.value.busy)
    }

    @Test fun failedAutomaticRestorationRetainsSettingsForExplicitRetry() = runBlocking {
        val actions = Actions().apply { fail = "restore-headlights" }
        val session = CampModeSession(actions, saved, completedSteps = CampStep.entries.toSet())
        session.onScreenWake()
        assertTrue(session.state.value.active)
        assertFalse(session.state.value.awaitingWake)
        assertEquals(saved, actions.stored)
        assertTrue(session.state.value.report.contains("Restoration incomplete"))
        session.onScreenWake()
        assertEquals("Duplicate wake signals must not repeatedly restore settings", 4,
            actions.calls.count { it.startsWith("restore-") })
        actions.fail = null
        session.exit()
        assertFalse(session.state.value.active)
        assertNull(actions.stored)
    }

    @Test fun rejectedOrThrowingScreenOffAllowsRestorationRetryWithoutWaitingForWake() = runBlocking {
        for (throws in listOf(false, true)) {
            val actions = Actions().apply {
                if (throws) onScreen = { throw IOException("Screen command unavailable") } else fail = "screen"
            }
            val session = CampModeSession(actions)
            session.enter()
            assertTrue(session.state.value.active)
            assertFalse(session.state.value.awaitingWake)
            assertFalse(session.state.value.busy)
            assertFalse(CampStep.SCREEN in session.state.value.completedSteps)
            assertTrue(session.state.value.report.contains(
                if (throws) "Screen command unavailable" else "NOT CONFIRMED",
            ))
            session.exit()
            assertFalse(session.state.value.active)
        }
    }
}
