package com.sphy.airconcontroller.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CampModeApiInventoryTest {
    class ExampleDevice {
        fun setWindowState(area: Int, state: Int): Int = error("Must not invoke vehicle methods: $area $state")
        fun getINSTheme(): Int = error("Must not invoke vehicle methods")
        fun setHeadlightControlMode(mode: Int): Int = error("Must not invoke vehicle methods: $mode")
        fun getAmbientLux(): Int = error("Must not invoke vehicle methods")
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
        assertTrue(report.contains("int setHeadlightControlMode(int)"))
        assertTrue(report.contains("int getAmbientLux()"))
        assertTrue(report.contains("WINDOW_CLOSED"))
        assertFalse(report.contains("unrelated()"))
        assertFalse(report.contains("UNRELATED"))
        assertTrue(report.contains("Class unavailable: missing.Device"))
    }

    class LightingDevice {
        var writes = 0
        fun getHeadlightControlMode() = 0
        fun getAmbientLux() = -999999999
        fun isLowBeamOn() = true
        fun setHeadlightControlMode(mode: Int): Int { writes++; return mode }
        fun getBeamState(area: Int): Int = error("Do not guess arguments: $area")
        fun getLightingAdapter(): Any = error("Do not acquire arbitrary adapters")
        fun getAndResetLux(): Int = error("Do not invoke mutating reads")
        fun unrelated(): Int = error("Do not invoke unrelated reads")
    }

    @Test fun lightingCaptureReadsOnlyScalarZeroArgumentGettersAndPreservesInvalidRawValues() {
        val device = LightingDevice()
        val values = linkedMapOf<String, String>()
        readLightingGetters("Light", device) { key, getter -> values[key] = getter().toString() }
        assertEquals(mapOf(
            "Light.getAmbientLux" to "-999999999",
            "Light.getHeadlightControlMode" to "0",
            "Light.isLowBeamOn" to "true",
        ), values)
        assertEquals(0, device.writes)
    }
}
