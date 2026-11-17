package com.sphy.airconcontroller.byd

import android.hardware.bydauto.BYDAutoEventValue
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

    open class LegacyFeedback(var current: Int = 2) {
        var returnEvent = false
        protected fun get(ids: IntArray, type: Class<*>): Any {
            check(ids.contentEquals(intArrayOf(1011875880)))
            check(type == Int::class.javaPrimitiveType)
            return if (returnEvent) BYDAutoEventValue().also { it.intValue = current } else current
        }
    }

    open class LegacySelector(current: Int = 2) : LegacyFeedback(current) {
        val writes = mutableListOf<Int>()
        var result = 0
        fun set(ids: IntArray, event: BYDAutoEventValue): Int {
            check(ids.contentEquals(intArrayOf(1276153912)))
            writes += event.intValue
            if (result == 0) current = event.intValue
            return result
        }
    }

    class RejectedNamedSelector : LegacySelector() {
        var throwOnWrite = false
        fun getHeadlightControlMode(): Int = 0
        fun setHeadlightControlMode(mode: Int): Int {
            check(mode == 1)
            if (throwOnWrite) throw SecurityException("instrument write denied")
            return -1
        }
    }

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

    @Test fun legacySelectorReadsInheritedFeedbackAndTurnsOffAndRestoresUsingVerifiedFeatureIds() {
        for (returnEvent in listOf(false, true)) {
            val selector = LegacySelector().apply { this.returnEvent = returnEvent }
            val controller = controller(selector)
            val saved = controller.readMode()
            assertEquals(2, saved.mode)
            assertTrue(saved.detail.contains("Instrument headlight feedback=2"))
            assertTrue(controller.setMode(1).success)
            assertEquals(1, controller.mode())
            assertTrue(controller.setMode(saved.mode!!).success)
            assertEquals(2, controller.mode())
            assertEquals(listOf(1, 2), selector.writes)
        }
    }

    @Test fun unreadableLegacyFeedbackNeverSendsACommandOrAssumesOff() {
        for (mode in listOf(0, -1, 255)) {
            val selector = LegacySelector(mode)
            val controller = controller(selector)
            val reading = controller.readMode()
            assertNull(reading.mode)
            assertTrue(reading.detail.contains("Invalid headlight selector reading: $mode"))
            assertFalse(controller.setMode(1).success)
            assertTrue(selector.writes.isEmpty())
        }
    }

    @Test fun rejectedOrThrowingNamedSetterNeverFallsBackToGenericWrite() {
        for (throwOnWrite in listOf(false, true)) {
            val selector = RejectedNamedSelector().apply { this.throwOnWrite = throwOnWrite }
            val controller = controller(selector)
            assertEquals(2, controller.mode())
            val result = controller.setMode(1)
            assertFalse(result.success)
            if (throwOnWrite) assertTrue(result.detail.contains("SecurityException: instrument write denied"))
            assertTrue(selector.writes.isEmpty())
            assertEquals(2, controller.mode())
        }
    }

    @Test fun rejectedLegacyWriteAndBindingFailuresHaveActionableDiagnostics() {
        val selector = LegacySelector().apply { result = -1 }
        val result = controller(selector).setMode(1)
        assertFalse(result.success)
        assertTrue(result.detail.contains("result=-1"))
        assertEquals(2, controller(selector).mode())
        val denied = BydHeadlightController({ throw SecurityException("instrument read denied") }, { _, _ -> })
        val reading = denied.readMode()
        assertNull(reading.mode)
        assertTrue(reading.detail.contains("Instrument binding: SecurityException: instrument read denied"))
        assertTrue(denied.setMode(1).detail.contains("instrument read denied"))
    }
}
