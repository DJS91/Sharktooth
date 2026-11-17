package com.sphy.airconcontroller.byd

import org.junit.Assert.*
import org.junit.Test

class BydHeadlightControllerTest {
    class Selector(var current: Int = 2) {
        val writes = mutableListOf<Int>()
        var result = 0
        fun getHeadlightControlMode() = current
        fun setHeadlightControlMode(mode: Int): Int {
            writes += mode
            if (result == 0) current = mode
            return result
        }
    }

    private fun controller(device: Any?) = BydHeadlightController({ device }, { _, _ -> })

    @Test fun turnsOffAndRestoresOriginalAutoSelection() {
        val selector = Selector()
        val controller = controller(selector)
        val saved = controller.mode()!!
        assertTrue(controller.setMode(BydHeadlightController.OFF).success)
        assertEquals(BydHeadlightController.OFF, controller.mode())
        assertTrue(controller.setMode(saved).success)
        assertEquals(saved, controller.mode())
        assertEquals(listOf(1, 2), selector.writes)
    }

    @Test fun rejectedWriteDoesNotReportSuccessOrChangeSelection() {
        val selector = Selector(4).apply { result = -1 }
        val controller = controller(selector)
        assertFalse(controller.setMode(1).success)
        assertEquals(4, controller.mode())
    }

    @Test fun missingSdkAndInvalidReadingsAreNotInterpretedAsOff() {
        for (device in listOf(null, Any(), Selector(0), Selector(-1), Selector(255))) {
            assertNull(controller(device).mode())
        }
        assertFalse(controller(null).setMode(1).success)
        assertFalse(controller(Any()).setMode(1).success)
    }

    @Test fun invalidModeIsRejectedWithoutSendingCommand() {
        val selector = Selector()
        assertThrows(IllegalArgumentException::class.java) { controller(selector).setMode(0) }
        assertTrue(selector.writes.isEmpty())
    }
}
