package com.sphy.airconcontroller.camp

/** Recognizes only the centre-display OFF -> ON pair captured from the car's TsCarPower log. */
internal class CampBacklightWakeTracker(
    private val sinceMillis: Long,
    private var sawOff: Boolean = false,
) {
    fun woke(log: String): Boolean {
        for (line in log.lineSequence()) {
            val event = EVENT.find(line) ?: continue
            val timestamp = event.groupValues[1]
            val timeMillis = timestamp.substringBefore('.').toLong() * 1000 +
                timestamp.substringAfter('.').take(3).padEnd(3, '0').toLong()
            if (timeMillis < sinceMillis) continue
            when (event.groupValues[2]) {
                "2" -> sawOff = true
                "1" -> if (sawOff) return true
            }
        }
        return false
    }

    private companion object {
        val EVENT = Regex(
            """^(\d+\.\d+)\s+.*\bTsCarPower\b.*\bsetBackLightState\b.*\bpowerState\s*:\s*([12])\b.*\btype\s*:\s*(?:type\s*:\s*)?1\b""",
        )
    }
}
