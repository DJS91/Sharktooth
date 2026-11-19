package com.sphy.airconcontroller.ui

internal class TripUsageTracker(var state: State = State()) {
    data class State(
        val lastGear: Int? = null,
        val startCharge: Double? = null,
        val startFuel: Double? = null,
        val startedAtMs: Long? = null,
        val startOdometerKm: Double? = null,
    ) {
        fun distanceKm(odometerKm: Double?): Double? {
            val start = startOdometerKm ?: return null
            return odometerKm?.takeIf { it.isFinite() && it >= start }?.minus(start)
        }
    }

    fun sample(ready: Boolean?, gear: Int?, charge: Double?, fuel: Double?, nowMs: Long, odometerKm: Double?) {
        if (ready == false) {
            state = state.copy(lastGear = null)
            return
        }
        if (ready != true) return
        if (state.lastGear == 1 && gear == 4) {
            state = state.copy(
                startCharge = validPercent(charge), startFuel = validPercent(fuel),
                startedAtMs = nowMs,
                startOdometerKm = odometerKm?.takeIf { it.isFinite() && it >= 0.0 },
            )
        }
        state = state.copy(lastGear = gear)
    }

    private fun validPercent(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it in 0.0..100.0 }
}
