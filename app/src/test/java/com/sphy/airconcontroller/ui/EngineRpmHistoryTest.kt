package com.sphy.airconcontroller.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineRpmHistoryTest {
    @Test fun keepsOnlyTheLastHourAndPreservesMissingReadings() {
        val history = EngineRpmHistory()
        history.record(0, 900.0)
        history.record(1_000, 1_500.0)
        history.record(2_000, null)
        val now = EngineRpmHistory.HOUR_MS + 1_000
        assertEquals(
            listOf(
                EngineRpmHistory.Sample(1_000, 1_500.0),
                EngineRpmHistory.Sample(2_000, null),
                EngineRpmHistory.Sample(now, 0.0),
            ),
            history.record(now, 0.0),
        )
    }
}
