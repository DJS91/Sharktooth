package com.sphy.airconcontroller.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineRpmHistoryTest {
    @Test fun keepsThirtyMinutesAndPreservesMissingReadingsAcrossWindows() {
        val history = EngineRpmHistory()
        history.record(0, 900.0)
        history.record(1_000, 1_500.0)
        history.record(2_000, null)
        val now = EngineRpmHistory.MAX_HISTORY_MS + 1_000
        history.record(now, 0.0)
        assertEquals(
            listOf(
                EngineRpmHistory.Sample(1_000, 1_500.0),
                EngineRpmHistory.Sample(2_000, null),
                EngineRpmHistory.Sample(now, 0.0),
            ),
            history.since(now, EngineRpmHistory.MAX_HISTORY_MS),
        )
        assertEquals(listOf(EngineRpmHistory.Sample(now, 0.0)),
            history.since(now, EngineRpmHistory.TEN_SECONDS_MS))
    }

    @Test fun windowsIncludeTheirBoundaryAndPreserveUnavailableSamples() {
        val history = EngineRpmHistory()
        val now = 400_000L
        history.record(now - 300_001, 800.0)
        history.record(now - 300_000, 900.0)
        history.record(now - 10_000, Double.NaN)
        history.record(now, 1_600.0)
        assertEquals(listOf(
            EngineRpmHistory.Sample(now - 300_000, 900.0),
            EngineRpmHistory.Sample(now - 10_000, null),
            EngineRpmHistory.Sample(now, 1_600.0),
        ), history.since(now, EngineRpmHistory.FIVE_MINUTES_MS))
        assertEquals(listOf(
            EngineRpmHistory.Sample(now - 10_000, null),
            EngineRpmHistory.Sample(now, 1_600.0),
        ), history.since(now, EngineRpmHistory.TEN_SECONDS_MS))
    }
}
