package com.sphy.airconcontroller.byd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydWindowControllerTest {
    private fun windows(device: () -> Any?) = BydWindowController(device) { _, _ -> }

    class LegacyBodywork {
        val calls = mutableListOf<List<Int>>()
        fun setAllWindowState(a: Int, b: Int, c: Int, d: Int): Int { calls += listOf(a, b, c, d); return 0 }
        fun getWindowOpenPercent(area: Int): Int = if (area == 1) 40 else 65535
    }

    class Dilink5Bodywork {
        val calls = mutableListOf<List<Int>>()
        fun setAllWindowState(a: Int, b: Int, c: Int, d: Int): Int = throw RuntimeException("Stub!")
        fun setWindowControlState(states: IntArray): Int { calls += states.toList(); return 0 }
    }

    class RefusingBodywork {
        fun setAllWindowState(a: Int, b: Int, c: Int, d: Int): Int = -1
        fun setWindowControlState(states: IntArray): Int = throw AssertionError("must not fall back after a refusal")
    }

    @Test fun legacyCarsCloseAndStopAllFourWindows() {
        val dev = LegacyBodywork()
        val windows = windows { dev }
        assertTrue(windows.closeAll().success)
        assertTrue(windows.stopAll().success)
        assertEquals(listOf(listOf(2, 2, 2, 2), listOf(3, 3, 3, 3)), dev.calls)
    }

    @Test fun dilink5StubFallsBackToWindowControlState() {
        val dev = Dilink5Bodywork()
        val result = windows { dev }.closeAll()
        assertTrue(result.success)
        assertEquals("setWindowControlState([2, 2, 2, 2])", result.method)
        assertEquals(listOf(listOf(2, 2, 2, 2)), dev.calls)
    }

    @Test fun refusalIsReportedAndInvalidPositionsAreUnknown() {
        assertFalse(windows { RefusingBodywork() }.closeAll().success)
        assertFalse(windows { null }.closeAll().success)
        val positions = windows { LegacyBodywork() }.positions()
        assertEquals(40, positions["Front left"])
        assertNull(positions["Front right"])
    }
}
