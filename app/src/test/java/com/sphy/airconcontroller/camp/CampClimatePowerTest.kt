package com.sphy.airconcontroller.camp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CampClimatePowerTest {
    @Test fun alreadyOffDoesNotSendACommandOnEntryOrExit() = runBlocking {
        var reads = 0
        val climate = CampClimatePower(
            readPower = { reads++; false },
            stop = { error("Already-OFF climate must not be rewritten") },
        )
        repeat(2) { assertTrue(climate.off().success) }
        assertEquals(4, reads)
    }

    @Test fun delayedOffReadBackSupersedesAnEarlierCommandFailure() = runBlocking {
        var reads = 0
        var commands = 0
        val climate = CampClimatePower(
            readPower = { ++reads < 4 },
            stop = { commands++; CampResult(false, "power state did not change") },
        )
        val result = climate.off()
        assertTrue(result.detail, result.success)
        assertTrue(result.detail.contains("command: false"))
        assertEquals(1, commands)
        assertEquals(5, reads)
    }

    @Test fun acceptedButIgnoredCommandOrUnreadablePowerCannotConfirmOff() = runBlocking {
        for (power in listOf(true, null)) {
            var commands = 0
            val climate = CampClimatePower(
                readPower = { power },
                stop = { commands++; CampResult(true, "accepted") },
            )
            val result = climate.off()
            assertFalse(result.success)
            assertTrue(result.detail.contains("not confirmed"))
            assertEquals(1, commands)
        }
    }

    @Test fun transientOffFollowedByOnStillSendsStopAndWaitsForStableOff() = runBlocking {
        val readings = ArrayDeque(listOf(false, true, false, true, false, false))
        var commands = 0
        val climate = CampClimatePower(
            readPower = { readings.removeFirst() },
            stop = { commands++; CampResult(true, "accepted") },
        )
        assertTrue(climate.off().success)
        assertEquals(1, commands)
        assertTrue(readings.isEmpty())
    }
}
