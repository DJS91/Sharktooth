package com.sphy.airconcontroller.byd

import com.sphy.airconcontroller.storage.AdasCustomProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class BydAdasControllerTest {
    private val snapshot = BydAdasController.Snapshot(
        sdkInjected = true, diPilotBound = true, bindError = null,
        elka = true, elkaRaw = 2,
        laneDeparture = BydAdasController.LaneDepartureMode.BOTH, laneDepartureRaw = 3,
        aeb = true, aebRaw = 1, driverMonitor = true, driverMonitorRaw = 1,
    )

    @Test fun existingProfilesDoNotRequireOrChangeAnUnconfiguredFcwSetting() {
        val profile = AdasCustomProfile()
        assertEquals(BydAdasController.ProfileCheck.MATCH, snapshot.compareProfile(profile))
        assertEquals(BydAdasController.ProfileCheck.MATCH,
            snapshot.copy(forwardCollision = BydAdasController.ForwardCollisionMode.OFF).compareProfile(profile))
    }

    @Test fun configuredFcwMustMatchAReadableVehicleState() {
        val profile = AdasCustomProfile(forwardCollision = BydAdasController.ForwardCollisionMode.MODERATE)
        assertEquals(BydAdasController.ProfileCheck.UNKNOWN, snapshot.compareProfile(profile))
        assertEquals(BydAdasController.ProfileCheck.MISMATCH,
            snapshot.copy(forwardCollision = BydAdasController.ForwardCollisionMode.LATE).compareProfile(profile))
        assertEquals(BydAdasController.ProfileCheck.MATCH,
            snapshot.copy(forwardCollision = BydAdasController.ForwardCollisionMode.MODERATE).compareProfile(profile))
    }

    @Test fun matchingFcwDoesNotHideAnotherMismatchOrAnUnboundVehicle() {
        val profile = AdasCustomProfile(forwardCollision = BydAdasController.ForwardCollisionMode.EARLY)
        val matching = snapshot.copy(forwardCollision = BydAdasController.ForwardCollisionMode.EARLY)
        assertEquals(BydAdasController.ProfileCheck.MISMATCH, matching.copy(aeb = false).compareProfile(profile))
        assertEquals(BydAdasController.ProfileCheck.UNKNOWN, matching.copy(diPilotBound = false).compareProfile(profile))
    }
}
