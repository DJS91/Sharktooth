package com.sphy.airconcontroller.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampTestSnapshotTest {
    @Test fun comparesChangedValuesWithoutReportingUnchangedValues() {
        val before = CampTestSnapshot(mapOf("window" to "0", "theme" to "1"), emptyMap())
        val after = CampTestSnapshot(mapOf("window" to "25", "theme" to "1"), emptyMap())
        val diff = after.changesFrom(before)
        assertTrue(diff.contains("window: 0 -> 25"))
        assertFalse(diff.contains("theme"))
        assertFalse(diff.contains("No changes"))
    }

    @Test fun failedOrMissingReadsAreNotReportedAsStateChanges() {
        val before = CampTestSnapshot(mapOf("window" to "0"), mapOf("theme" to "denied"))
        val after = CampTestSnapshot(mapOf("theme" to "1"), mapOf("window" to "denied"))
        val diff = after.changesFrom(before)
        assertTrue(diff.contains("window: comparison unavailable"))
        assertTrue(diff.contains("theme: comparison unavailable"))
        assertTrue(diff.contains("does not prove"))
        assertFalse(diff.contains("->"))
        assertTrue(after.report().contains("window: UNAVAILABLE (denied)"))
    }
}
