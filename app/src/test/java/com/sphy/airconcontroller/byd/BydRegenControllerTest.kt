package com.sphy.airconcontroller.byd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BydRegenControllerTest {
    class Setting(var mode: Int = STANDARD, private val applies: Boolean = true, private val result: Int = 0) {
        companion object {
            const val STANDARD = 13
            const val LARGE = 29
            @JvmField val SET_DR_ENERGY_FB_STANDARD = STANDARD
            @JvmField val SET_DR_ENERGY_FB_LARGE = LARGE
        }
        val writes = mutableListOf<Int>()
        fun getEnergyFeedback() = mode
        fun setEnergyFeedback(value: Int): Int {
            writes += value
            if (applies && result >= 0) mode = value
            return result
        }
    }
    private fun controller(setting: Any?) = BydRegenController({ setting }, {}, { _, _ -> })

    @Test fun usesExportedConstantsAndConfirmsVehicleReadback() = runBlocking {
        val setting = Setting()
        val controller = controller(setting)
        assertEquals(BydRegenController.Mode.STANDARD, controller.read().mode)
        assertTrue(controller.setMode(BydRegenController.Mode.LARGER).success)
        assertEquals(listOf(Setting.LARGE), setting.writes)
        assertEquals(BydRegenController.Mode.LARGER, controller.read().mode)
        assertTrue(controller.setMode(BydRegenController.Mode.LARGER).success)
        assertEquals(1, setting.writes.size)
    }

    @Test fun unavailableModePreventsWrites() = runBlocking {
        val setting = Setting(mode = -999)
        assertNull(controller(setting).read().mode)
        assertFalse(controller(setting).setMode(BydRegenController.Mode.LARGER).success)
        assertTrue(setting.writes.isEmpty())
        assertFalse(controller(null).setMode(BydRegenController.Mode.LARGER).success)
        assertNull(controller(Any()).read().mode)
    }

    @Test fun rejectedOrIgnoredCommandsNeverLookSuccessful() = runBlocking {
        assertFalse(controller(Setting(applies = false)).setMode(BydRegenController.Mode.LARGER).success)
        assertFalse(controller(Setting(result = -1)).setMode(BydRegenController.Mode.LARGER).success)
    }
}
