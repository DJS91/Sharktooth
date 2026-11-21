package com.sphy.airconcontroller.ui

import org.junit.Assert.*
import org.junit.Test

class FuelFillTrackerTest {
    @Test fun firstReadingAndSmallFluctuationsDoNotInventAFill() {
        val tracker = FuelFillTracker()
        tracker.sample(40.0, 1_000L)
        tracker.sample(44.9, 2_000L)
        tracker.sample(40.0, 3_000L)
        assertNull(tracker.state.lastFillAtMs)
        tracker.sample(45.0, 4_000L)
        assertEquals(4_000L, tracker.state.lastFillAtMs)
    }

    @Test fun gradualFillCountsOnceTheCumulativeIncreaseReachesFivePoints() {
        val tracker = FuelFillTracker()
        tracker.sample(40.0, 1_000L)
        tracker.sample(42.0, 2_000L)
        tracker.sample(44.0, 3_000L)
        assertNull(tracker.state.lastFillAtMs)
        tracker.sample(46.0, 4_000L)
        assertEquals(4_000L, tracker.state.lastFillAtMs)
        tracker.sample(46.0, 5_000L)
        assertEquals(4_000L, tracker.state.lastFillAtMs)
    }

    @Test fun restoredStateDetectsTheNextFillAfterFuelUse() {
        val tracker = FuelFillTracker(FuelFillTracker.State(70.0, 1_000L))
        tracker.sample(30.0, 2_000L)
        assertEquals(1_000L, tracker.state.lastFillAtMs)
        val restored = FuelFillTracker(tracker.state)
        restored.sample(35.0, 3_000L)
        assertEquals(3_000L, restored.state.lastFillAtMs)
        assertEquals(35.0, restored.state.baselinePct)
    }

    @Test fun missingAndInvalidReadingsDoNotEraseTheBaselineOrTimestamp() {
        val saved = FuelFillTracker.State(40.0, 1_000L)
        val tracker = FuelFillTracker(saved)
        for (fuel in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 101.0)) {
            tracker.sample(fuel, 2_000L)
            assertEquals(saved, tracker.state)
        }
        tracker.sample(45.0, 3_000L)
        assertEquals(3_000L, tracker.state.lastFillAtMs)
    }
}
