package com.sphy.airconcontroller.ui

internal class RegenEnergySampler {
    private data class Sample(val day: Long, val elapsedMs: Long, val powerKw: Double)

    private var previous: Sample? = null

    fun reset() {
        previous = null
    }

    fun sample(day: Long, elapsedMs: Long, powerKw: Double?): Double {
        val last = previous
        val current = powerKw?.takeIf { it.isFinite() }?.let { Sample(day, elapsedMs, it) }
        previous = current
        if (last == null || current == null || last.day != day) return 0.0
        val gapMs = elapsedMs - last.elapsedMs
        if (gapMs !in 1..5_000) return 0.0

        // Integrate only the negative part of the linear interval between readings.
        val start = -last.powerKw
        val end = -current.powerKw
        val averageKw = when {
            start >= 0 && end >= 0 -> start / 2 + end / 2
            start <= 0 && end <= 0 -> 0.0
            else -> maxOf(start, end).let { peak -> peak / (peak - minOf(start, end)) * peak / 2 }
        }
        return averageKw * (gapMs / 3_600_000.0)
    }
}
