package com.sphy.airconcontroller.ui

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DashboardHistoryTest {
    private class Storage : DashboardHistory.Storage {
        val saved = mutableListOf<DashboardHistory.Sample>()
        override fun load(start: Long, end: Long) = saved.filter { it.time in start until end }
        override fun save(start: Long, sample: DashboardHistory.Sample) {
            saved.removeAll { it.time < start }
            saved += sample
        }
    }
    private val zone = ZoneId.of("Australia/Brisbane")
    private val now = Instant.parse("2026-11-21T05:00:00Z").toEpochMilli()

    @Test fun currentDaySurvivesRestartAndResetsAtLocalMidnight() {
        val storage = Storage()
        val history = DashboardHistory(storage)
        assertTrue(history.record(now, mapOf("regen" to 1.0), zone))
        assertFalse(history.record(now + 5_000, mapOf("regen" to 1.2), zone))
        assertTrue(history.record(now + 10_000, mapOf("regen" to 1.3), zone))
        val restored = DashboardHistory(storage)
        assertEquals(listOf(1.0, 1.3), restored.samples(now + 10_000, zone).map { it.values["regen"] })
        val midnight = DashboardHistory.dayBounds(now, zone).second
        assertTrue(restored.samples(midnight, zone).isEmpty())
        assertTrue(restored.record(midnight, mapOf("regen" to 0.0), zone))
        assertEquals(listOf(0.0), DashboardHistory(storage).samples(midnight, zone).map { it.values["regen"] })
        assertEquals(1, storage.saved.size)
    }

    @Test fun missingAndInvalidValuesRemainGapsAndClockRollbackDoesNotReorderSamples() {
        val history = DashboardHistory(Storage())
        history.record(now, mapOf("speed" to null, "regen" to Double.NaN), zone)
        history.record(now + 60_000, mapOf("speed" to 45.0, "regen" to 1.0), zone)
        assertFalse(history.record(now + 30_000, mapOf("speed" to 20.0), zone))
        val samples = history.samples(now + 60_000, zone)
        assertNull(samples[0].values["speed"])
        assertNull(samples[0].values["regen"])
        assertTrue(samples[1].time - samples[0].time > DashboardHistory.GAP_MS)
    }

    @Test fun localDayBoundsAccountForDaylightSaving() {
        val sydney = ZoneId.of("Australia/Sydney")
        val spring = DashboardHistory.dayBounds(Instant.parse("2026-10-04T02:00:00Z").toEpochMilli(), sydney)
        val autumn = DashboardHistory.dayBounds(Instant.parse("2026-04-05T02:00:00Z").toEpochMilli(), sydney)
        assertEquals(23 * 3_600_000L, spring.second - spring.first)
        assertEquals(25 * 3_600_000L, autumn.second - autumn.first)
    }

    @Test fun failedStorageDoesNotPretendASampleWasSaved() {
        val storage = object : DashboardHistory.Storage {
            override fun load(start: Long, end: Long) = emptyList<DashboardHistory.Sample>()
            override fun save(start: Long, sample: DashboardHistory.Sample) { throw IllegalStateException("disk unavailable") }
        }
        val history = DashboardHistory(storage)
        assertThrows(IllegalStateException::class.java) { history.record(now, mapOf("regen" to 1.0), zone) }
        assertTrue(history.samples(now, zone).isEmpty())
    }
}
