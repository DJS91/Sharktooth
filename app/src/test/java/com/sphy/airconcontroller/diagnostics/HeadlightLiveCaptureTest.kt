package com.sphy.airconcontroller.diagnostics

import org.junit.Assert.*
import org.junit.Test

class HeadlightLiveCaptureTest {
    private fun snapshot(vararg values: Pair<String, String>) = CampTestSnapshot(mapOf(*values), emptyMap())

    @Test fun onlyChangedReadingsAppearAndInvalidSelectorIsNeverOff() {
        val capture = HeadlightLiveCapture(100_000, 42)
        val baseline = snapshot("Headlights.selector" to "0", "Instrument.getHeadlightControlMode" to "0")
        capture.sample(baseline, "10:00:00")
        capture.sample(baseline, "10:00:01")
        assertTrue(capture.report().contains("Selector: unavailable (not OFF)"))
        assertTrue(capture.report().contains("Latest 0 of 0 events"))
        capture.sample(snapshot("Headlights.selector" to "2", "Instrument.getHeadlightControlMode" to "2"), "10:00:02")
        val report = capture.report()
        assertTrue(report.contains("Selector: AUTO (2)"))
        assertTrue(report.contains("Instrument.getHeadlightControlMode: 0 -> 2"))
        assertTrue(report.contains("NOT verified commands"))
        assertFalse(report.contains("SDK raw Headlights.readPaths"))
    }

    @Test fun logCaptureIgnoresOldUnrelatedOwnAndOverlappingLines() {
        val capture = HeadlightLiveCapture(100_000, 42)
        val relevant = "100.500 200 201 I CarLight: headlight stalk AUTO"
        capture.logs("""
            99.000 200 201 I CarLight: headlight OLD
            100.100 42 43 W Sharktooth: headlight own SDK read
            100.200 200 201 I Wifi: signal changed
            $relevant
        """.trimIndent())
        capture.logs(relevant)
        val report = capture.report()
        assertTrue(report.contains("Latest 1 of 1 events"))
        assertTrue(report.contains("CarLight: headlight stalk AUTO"))
        assertFalse(report.contains("OLD"))
        assertFalse(report.contains("own SDK"))
        assertFalse(report.contains("Wifi"))
    }

    @Test fun busyLogsRemainBoundedButReturnToAnEarlierStalkPositionIsShown() {
        val capture = HeadlightLiveCapture(100_000, 42)
        repeat(1000) { i -> capture.logs("${101 + i}.000 200 201 I CarLight: headlight state $i " + "x".repeat(300)) }
        capture.logs("1200.000 200 201 I CarLight: headlight stalk OFF")
        capture.logs("1201.000 200 201 I CarLight: headlight stalk AUTO")
        capture.logs("1202.000 200 201 I CarLight: headlight stalk OFF")
        val report = capture.report()
        assertTrue(report.contains("Latest 8 of 1003 events"))
        assertEquals(2, report.lineSequence().count { it.endsWith("headlight stalk OFF") })
        assertTrue("The whole live view must remain short", report.length < 2200)
        assertTrue(report.lines().size <= 16)
    }

    @Test fun readFailuresAreVisibleOnceAndRecoveryIsAChange() {
        val capture = HeadlightLiveCapture(100_000, 42)
        capture.logStatus = "unavailable; SDK readings only (ADB not authorized)"
        val failed = CampTestSnapshot(emptyMap(), mapOf("Headlights.selector" to "sentinel -999999999"))
        repeat(100) { capture.sample(failed, "10:00:00") }
        assertTrue(capture.report().contains("ADB not authorized"))
        assertTrue(capture.report().contains("Latest 0 of 0 events"))
        capture.sample(snapshot("Headlights.selector" to "1"), "10:00:01")
        assertTrue(capture.report().contains("unavailable -> 1"))
        capture.stopped = true
        assertTrue(capture.report().startsWith("STOPPED"))
    }

    @Test fun bodyworkRearLampChangesRemainVisibleWithoutGuessingHeadlightMode() {
        val capture = HeadlightLiveCapture(100_000, 42)
        capture.sample(snapshot("Bodywork.getRearLampLightState" to "1"), "07:25:00")
        capture.sample(snapshot("Bodywork.getRearLampLightState" to "2"), "07:25:02")
        assertTrue(capture.report().contains("SDK raw Bodywork.getRearLampLightState: 1 -> 2"))
        repeat(20) { capture.logs("${101 + it}.000 200 201 I CarLight: headlight event $it") }
        val report = capture.report()
        assertTrue(report.contains("Rear-lamp feedback (raw): 2"))
        assertTrue("The header must retain the reading even after its change event scrolls out",
            report.contains("Latest 8 of 21 events"))
        assertTrue(report.contains("Selector: unavailable (not OFF)"))
        assertFalse(report.contains("Selector: OFF"))
        capture.sample(CampTestSnapshot(emptyMap(), mapOf("Bodywork.getRearLampLightState" to "denied")), "07:25:30")
        assertTrue(capture.report().contains("Rear-lamp feedback (raw): unavailable"))
    }
}
