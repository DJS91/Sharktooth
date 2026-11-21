package com.sphy.airconcontroller.ui

internal class FuelFillTracker(var state: State = State()) {
    data class State(val baselinePct: Double? = null, val lastFillAtMs: Long? = null)

    fun sample(fuelPct: Double?, nowMs: Long) {
        val fuel = fuelPct?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return
        val baseline = state.baselinePct
        state = when {
            baseline == null || fuel < baseline -> state.copy(baselinePct = fuel)
            fuel - baseline >= 5.0 -> State(fuel, nowMs)
            else -> state
        }
    }
}
