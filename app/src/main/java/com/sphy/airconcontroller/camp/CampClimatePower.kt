package com.sphy.airconcontroller.camp

import kotlinx.coroutines.delay

internal class CampClimatePower(
    private val readPower: suspend () -> Boolean?,
    private val stop: suspend () -> CampResult,
) {
    suspend fun off(): CampResult {
        if (readPower() == false) {
            delay(250)
            if (readPower() == false) return CampResult(true, "Climate already OFF; no climate settings changed.")
        }
        val command = stop()
        var consecutiveOff = 0
        var power: Boolean? = null
        repeat(12) {
            delay(250)
            power = readPower()
            consecutiveOff = if (power == false) consecutiveOff + 1 else 0
            if (consecutiveOff >= 2) {
                return CampResult(true, "Climate OFF confirmed by consecutive read-backs; command: ${command.success} ${command.detail}")
            }
        }
        return CampResult(false, "Climate OFF not confirmed; power=${power ?: "unavailable"}; command: ${command.success} ${command.detail}")
    }
}
