package com.sphy.airconcontroller.camp

import com.sphy.airconcontroller.byd.BydVehicleInfoController.GearReads
import org.junit.Assert.*
import org.junit.Test

class CampModeSafetyTest {
    @Test fun nonParkGearRequestsOneExitIncludingConflictingParkSignals() {
        for (gear in listOf(GearReads(2, 2), GearReads(3, null), GearReads(4, 1),
            GearReads(null, 5), GearReads(6, 6))) {
            val safety = CampModeSafety()
            assertEquals(CampModeSafety.ExitReason.LEFT_PARK, safety.sample(true, gear, true))
            assertNull("Do not repeatedly retry a failed restoration on every tick", safety.sample(true, gear, true))
        }
    }

    @Test fun unknownSignalsDoNotInventDepartureOrAStartup() {
        val safety = CampModeSafety()
        for (gear in listOf(null, GearReads(null, null), GearReads(-1, 99), GearReads(1, 1))) {
            assertNull(safety.sample(true, gear, null))
            assertNull(safety.sample(true, gear, true))
        }
    }

    @Test fun powerOffWaitsForConfirmedReadyAndSurvivesMissingReadings() {
        val safety = CampModeSafety()
        assertNull(safety.sample(true, GearReads(1, 1), false))
        assertNull(safety.sample(true, GearReads(4, 4), false))
        assertNull(safety.sample(true, null, null))
        assertEquals(CampModeSafety.ExitReason.VEHICLE_RESTARTED, safety.sample(true, GearReads(1, 1), true))
        assertNull(safety.sample(true, GearReads(1, 1), true))
    }

    @Test fun recoveredAndSuspendedSessionsWaitForReadyRatherThanResumingCamp() {
        val recovered = CampModeSafety(recoveredSession = true)
        assertNull(recovered.sample(true, GearReads(1, 1), null))
        assertEquals(CampModeSafety.ExitReason.SESSION_RECOVERED, recovered.sample(true, GearReads(1, 1), true))
        val suspended = CampModeSafety()
        assertNull(suspended.sample(true, null, null, resumedAfterSleep = true))
        assertEquals(CampModeSafety.ExitReason.SESSION_RECOVERED, suspended.sample(true, GearReads(1, 1), true))
    }

    @Test fun newSessionsAndLaterPowerCyclesCanTriggerTheirOwnSafetyExit() {
        val safety = CampModeSafety()
        assertEquals(CampModeSafety.ExitReason.LEFT_PARK, safety.sample(true, GearReads(4, 4), true))
        safety.reset()
        assertNull(safety.sample(true, GearReads(1, 1), true))
        assertEquals(CampModeSafety.ExitReason.LEFT_PARK, safety.sample(true, GearReads(4, 4), true))
        assertNull(safety.sample(true, GearReads(1, 1), false))
        assertEquals(CampModeSafety.ExitReason.VEHICLE_RESTARTED, safety.sample(true, GearReads(1, 1), true))
        assertNull(safety.sample(false, GearReads(4, 4), false))
        assertNull(safety.sample(true, GearReads(1, 1), true))
    }
}
