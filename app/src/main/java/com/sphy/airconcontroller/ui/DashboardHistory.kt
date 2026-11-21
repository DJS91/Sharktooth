package com.sphy.airconcontroller.ui

import java.time.Instant
import java.time.ZoneId

internal class DashboardHistory(private val storage: Storage) {
    data class Sample(val time: Long, val values: Map<String, Double?>)
    interface Storage {
        fun load(start: Long, end: Long): List<Sample>
        fun save(start: Long, sample: Sample)
    }

    private var dayStart: Long? = null
    private val items = ArrayList<Sample>()

    @Synchronized fun samples(now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Sample> {
        val (start, end) = dayBounds(now, zone)
        if (dayStart != start) {
            val loaded = storage.load(start, end)
            items.clear()
            items.addAll(loaded.filter { it.time in start until end }.sortedBy { it.time })
            dayStart = start
        }
        return items.filter { it.time <= now }
    }

    @Synchronized fun record(now: Long, values: Map<String, Double?>, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        samples(now, zone)
        val last = items.lastOrNull()
        if (last != null && now <= last.time) return false
        if (last != null && now - last.time < INTERVAL_MS) return false
        val sample = Sample(now, values.mapValues { (_, value) -> value?.takeIf { it.isFinite() } })
        storage.save(checkNotNull(dayStart), sample)
        items.add(sample)
        return true
    }

    companion object {
        const val INTERVAL_MS = 10_000L
        const val GAP_MS = 30_000L

        fun dayBounds(now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
            val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return date.atStartOfDay(zone).toInstant().toEpochMilli() to
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }
}
