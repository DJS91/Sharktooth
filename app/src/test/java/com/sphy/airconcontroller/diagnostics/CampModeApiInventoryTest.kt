package com.sphy.airconcontroller.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampModeApiInventoryTest {
    class ExampleDevice {
        fun setWindowState(area: Int, state: Int): Int = error("Must not invoke vehicle methods: $area $state")
        fun getINSTheme(): Int = error("Must not invoke vehicle methods")
        fun unrelated(): Int = error("Must not invoke vehicle methods")

        companion object {
            const val WINDOW_CLOSED = 1
            const val UNRELATED = 2
        }
    }

    @Test fun reportsRelevantMetadataWithoutCallingVehicleMethods() {
        val report = campModeApiInventory(
            ExampleDevice::class.java.classLoader!!,
            listOf("Example" to ExampleDevice::class.java.name, "Missing" to "missing.Device"),
        )
        assertTrue(report.contains("int setWindowState(int, int)"))
        assertTrue(report.contains("int getINSTheme()"))
        assertTrue(report.contains("WINDOW_CLOSED"))
        assertFalse(report.contains("unrelated()"))
        assertFalse(report.contains("UNRELATED"))
        assertTrue(report.contains("Class unavailable: missing.Device"))
    }
}
