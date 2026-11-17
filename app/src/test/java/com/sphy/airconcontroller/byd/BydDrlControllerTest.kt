package com.sphy.airconcontroller.byd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydDrlControllerTest {
    class LightDevice(var state: Int = 1, private val applies: Boolean = true) {
        val calls = mutableListOf<Int>()
        fun setDayTimeLightState(value: Int): Int { calls += value; if (applies) state = value; return 0 }
        fun getDayTimeLightState(): Int = state
    }

    private fun drl(device: () -> Any?) = BydDrlController(device) { _, _ -> }

    @Test fun offAndOnUseSdkValuesTwoAndOne() {
        val dev = LightDevice()
        val drl = drl { dev }
        assertTrue(drl.set(false).success)
        assertEquals(false, drl.isOn())
        assertTrue(drl.set(true).success)
        assertEquals(true, drl.isOn())
        assertEquals(listOf(2, 1), dev.calls)
    }

    @Test fun acceptedButIgnoredWriteIsVisibleInReadBack() {
        val drl = drl { LightDevice(applies = false) }
        assertTrue(drl.set(false).success)
        assertEquals(true, drl.isOn())
    }

    @Test fun missingDeviceAndInvalidStateAreUnknown() {
        assertFalse(drl { null }.set(false).success)
        assertNull(drl { null }.isOn())
        assertNull(drl { LightDevice(state = 65535) }.isOn())
    }
}
