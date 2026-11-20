package com.sphy.airconcontroller.camp

import com.sphy.airconcontroller.byd.BydVehicleInfoController

/** A recovered session is restored on READY instead of silently resuming vehicle suppression. */
internal class CampModeSafety(recoveredSession: Boolean = false) {
    enum class ExitReason { LEFT_PARK, VEHICLE_RESTARTED, SESSION_RECOVERED }

    private var restoreOnReady = if (recoveredSession) ExitReason.SESSION_RECOVERED else null
    private var exitRequested = false

    fun reset() {
        restoreOnReady = null
        exitRequested = false
    }

    fun sample(
        sessionActive: Boolean,
        gear: BydVehicleInfoController.GearReads?,
        ready: Boolean?,
        resumedAfterSleep: Boolean = false,
    ): ExitReason? {
        if (!sessionActive) {
            reset()
            return null
        }
        if (resumedAfterSleep) {
            exitRequested = false
            if (restoreOnReady == null) restoreOnReady = ExitReason.SESSION_RECOVERED
        }
        if (ready == false) {
            restoreOnReady = ExitReason.VEHICLE_RESTARTED
            exitRequested = false
            return null
        }
        if (exitRequested) return null
        val gears = listOfNotNull(gear?.gear, gear?.autoMode).filter { it in 1..6 }
        val reason = when {
            gears.any { it in 2..6 } -> ExitReason.LEFT_PARK
            ready == true -> restoreOnReady
            else -> null
        }
        if (reason != null) {
            exitRequested = true
            restoreOnReady = null
        }
        return reason
    }
}
