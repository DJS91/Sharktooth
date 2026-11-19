package com.sphy.airconcontroller.ui

import org.junit.Assert.*
import org.junit.Test

class TripUsageTrackerTest {
    private fun TripUsageTracker.read(
        gear: Int?, charge: Double? = 76.0, fuel: Double? = 60.0, ready: Boolean? = true,
        nowMs: Long = 1_000L, odometerKm: Double? = 1_000.0,
    ) = sample(ready, gear, charge, fuel, nowMs, odometerKm)

    @Test fun capturesLevelsImmediatelyOnParkToDriveWithoutWaitingForMovement() {
        val tracker = TripUsageTracker()
        tracker.read(1)
        tracker.read(4)
        assertEquals(76.0, tracker.state.startCharge)
        assertEquals(60.0, tracker.state.startFuel)
        tracker.read(4, charge = 75.0)
        assertEquals(76.0, tracker.state.startCharge)
    }

    @Test fun everyParkToDriveShiftResetsWithoutAPowerCycle() {
        val tracker = TripUsageTracker()
        tracker.read(1)
        tracker.read(4)
        tracker.read(1, charge = 70.0)
        assertEquals(76.0, tracker.state.startCharge)
        tracker.read(4, charge = 69.0, fuel = 55.0)
        assertEquals(69.0, tracker.state.startCharge)
        assertEquals(55.0, tracker.state.startFuel)
    }

    @Test fun nextPowerCycleReplacesTheBaselineOnDepartureNotAtPowerOff() {
        val tracker = TripUsageTracker()
        tracker.read(1)
        tracker.read(4)
        tracker.read(1, ready = false, charge = 70.0)
        assertEquals(76.0, tracker.state.startCharge)
        tracker.read(1, charge = 70.0)
        tracker.read(4, charge = 69.0)
        assertEquals(69.0, tracker.state.startCharge)
    }

    @Test fun restoredTripSurvivesReopeningUntilAnotherParkToDriveShift() {
        val original = TripUsageTracker()
        original.read(1)
        original.read(4)
        val restored = TripUsageTracker(original.state)
        restored.read(4, charge = 70.0)
        assertEquals(76.0, restored.state.startCharge)
        restored.read(1, charge = 70.0)
        restored.read(4, charge = 69.0)
        assertEquals(69.0, restored.state.startCharge)
    }

    @Test fun missingPowerOrGearDoesNotInventAShift() {
        val tracker = TripUsageTracker()
        tracker.read(4)
        assertNull(tracker.state.startCharge)
        tracker.read(1)
        tracker.read(4, ready = null)
        assertNull(tracker.state.startCharge)
        tracker.read(null)
        tracker.read(4)
        assertNull(tracker.state.startCharge)
        tracker.read(1)
        tracker.read(4)
        tracker.read(null, ready = null)
        assertEquals(76.0, tracker.state.startCharge)
        assertEquals(4, tracker.state.lastGear)
    }

    @Test fun missingDepartureLevelsStayUnknownInsteadOfBeingCapturedLater() {
        val tracker = TripUsageTracker()
        tracker.read(1)
        tracker.read(4, charge = Double.NaN, fuel = 101.0)
        tracker.read(4, charge = 75.0, fuel = 60.0)
        assertNull(tracker.state.startCharge)
        assertNull(tracker.state.startFuel)
        assertEquals(4, tracker.state.lastGear)
    }

    @Test fun tripTimeAndDistanceStartAtTheShiftAndResetOnTheNextShift() {
        val tracker = TripUsageTracker()
        tracker.read(1, nowMs = 1_000L, odometerKm = 1_000.0)
        tracker.read(4, nowMs = 2_000L, odometerKm = 1_000.5)
        tracker.read(4, nowMs = 62_000L, odometerKm = 1_012.5)
        assertEquals(2_000L, tracker.state.startedAtMs)
        assertEquals(12.0, tracker.state.distanceKm(1_012.5))
        tracker.read(1, odometerKm = 1_012.5)
        tracker.read(4, nowMs = 100_000L, odometerKm = 1_012.5)
        assertEquals(100_000L, tracker.state.startedAtMs)
        assertEquals(0.0, tracker.state.distanceKm(1_012.5))
    }

    @Test fun missingOrReversedOdometerDoesNotInventDistance() {
        val tracker = TripUsageTracker()
        tracker.read(1)
        tracker.read(4, odometerKm = null)
        assertNotNull(tracker.state.startedAtMs)
        assertNull(tracker.state.distanceKm(1_012.0))
        tracker.read(1)
        tracker.read(4)
        assertNull(tracker.state.distanceKm(null))
        assertNull(tracker.state.distanceKm(Double.NaN))
        assertNull(tracker.state.distanceKm(999.0))
    }
}
