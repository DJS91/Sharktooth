package com.sphy.airconcontroller.camp

import org.junit.Assert.*
import org.junit.Test

class CampBacklightWakeTrackerTest {
    private fun event(time: String, state: Int, type: Int = 1) =
        "$time  2118  2118 W TsCarPower: CarPowerBinder Pid:2764| setBackLightState [powerState: $state | type: $type]"

    @Test fun recognizesCapturedCentreBacklightOffThenOnIncludingRepeatedPolls() {
        val tracker = CampBacklightWakeTracker(2000)
        assertFalse(tracker.woke(event("2.001", 2)))
        assertFalse(tracker.woke(event("2.001", 2)))
        assertTrue(tracker.woke(event("2.001", 2) + "\n" + event("3.000", 1)))
    }

    @Test fun ignoresOldHistoryOtherDisplaysAndUnrelatedPowerMessages() {
        val tracker = CampBacklightWakeTracker(2000)
        assertFalse(tracker.woke(event("1.000", 2) + "\n" + event("1.500", 1)))
        assertFalse(tracker.woke(event("2.001", 2, 2) + "\n" + event("3.000", 1, 2)))
        assertFalse(tracker.woke(event("4.000", 1)))
        assertFalse(tracker.woke("5.000 W TsCarPower: WHAT_UN_TOUCH_VIEW_STATE iviBackLightState: 2"))
    }

    @Test fun catchesFastWakeInTheSamePollAndWakeAfterProcessRestart() {
        assertTrue(CampBacklightWakeTracker(2000).woke(
            event("2.001", 2) + "\n" + event("2.002", 1),
        ))
        assertTrue(CampBacklightWakeTracker(2000, sawOff = true).woke(event("2.001", 1)))
    }
}
