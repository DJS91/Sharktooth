package com.sphy.airconcontroller.byd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BydSaveSocControllerTest {
    class Charging(var state: Int = 1) {
        val writes = mutableListOf<Int>()
        fun getSocSaveSwitch() = state
        fun setSocSaveSwitch(value: Int): Int {
            writes += value
            state = value
            return 0
        }
    }

    open class Setting(var target: Int = 45) {
        var ignoreWrites = false
        val writes = mutableListOf<Int>()
        fun getSOCTarget() = target
        fun setSOCTarget(value: Int): Int {
            writes += value
            if (!ignoreWrites) target = value
            return 0
        }
    }

    class LimitedSetting : Setting() {
        companion object { const val SET_DR_SOC_TARGET_MAX = 70 }
    }

    private fun controller(charging: Charging, setting: Setting) =
        BydSaveSocController({ charging }, { setting }, pause = {}, log = { _, _ -> })

    @Test fun targetWritesRequireSaveSocOnAndStayWithinThePermittedRange() = runBlocking {
        val charging = Charging()
        val setting = Setting()
        val controller = controller(charging, setting)
        assertEquals(BydSaveSocController.Reading(false, 45, 100), controller.read())
        assertFalse(controller.setTarget(60))
        assertTrue(controller.setEnabled(true))
        assertFalse(controller.setTarget(24))
        assertFalse(controller.setTarget(101))
        assertTrue(controller.setTarget(25))
        assertTrue(controller.setTarget(100))
        assertTrue(controller.setEnabled(false))
        assertEquals(listOf(2, 1), charging.writes)
        assertEquals(listOf(25, 100), setting.writes)
    }

    @Test fun honoursFirmwareCapAndDoesNotConfirmAnIgnoredWrite() = runBlocking {
        val setting = LimitedSetting().apply { ignoreWrites = true }
        val controller = controller(Charging(2), setting)
        assertEquals(70, controller.read().maximum)
        assertFalse(controller.setTarget(71))
        assertFalse(controller.setTarget(70))
        assertEquals(listOf(70), setting.writes)
        assertEquals(45, controller.read().target)
    }

    @Test fun unknownSwitchStateDoesNotMasqueradeAsOffOrPermitWrites() = runBlocking {
        val charging = Charging(0)
        val setting = Setting()
        val controller = controller(charging, setting)
        assertNull(controller.read().enabled)
        assertFalse(controller.setEnabled(true))
        assertFalse(controller.setTarget(60))
        assertTrue(charging.writes.isEmpty())
        assertTrue(setting.writes.isEmpty())
    }
}
