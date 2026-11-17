package com.sphy.airconcontroller.byd

import android.hardware.bydauto.BYDAutoEventValue
import android.hardware.bydauto.BYDAutoFeatureIds.Instrument
import kotlinx.coroutines.runBlocking
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
        var needsEventToken = false
        var rejectPrimitiveToken = false
        var expectedFeedbackId = 1011875880
        val readTypes = mutableListOf<Class<*>>()
        protected fun get(ids: IntArray, type: Class<*>): Any {
            check(ids.contentEquals(intArrayOf(expectedFeedbackId)))
            readTypes += type
            if (type == Int::class.java && rejectPrimitiveToken) throw IllegalArgumentException("EventValue required")
            if (type == Int::class.java && needsEventToken) return -999999999
            if (type == BYDAutoEventValue::class.java) {
                return BYDAutoEventValue().also { it.intValue = if (needsEventToken) current else -999999999 }
            }
            check(type == Int::class.java)
            return if (returnEvent) BYDAutoEventValue().also { it.intValue = current } else current
        }
    }

    open class LegacySelector(current: Int = 2) : LegacyFeedback(current) {
        val writes = mutableListOf<Int>()
        var result = 0
        var expectedSetId = 1276153912
        fun set(ids: IntArray, event: BYDAutoEventValue): Int {
            check(ids.contentEquals(intArrayOf(expectedSetId)))
            writes += event.intValue
            if (result == 0) current = event.intValue
            return result
        }
    }

    class WarmingSelector {
        var reads = 0
        fun getHeadlightControlMode(): Int = if (++reads < 3) 0 else 2
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
        for (mode in listOf(0, -1, 255, -999999999)) {
            for (eventToken in listOf(false, true)) {
                val selector = LegacySelector(mode).apply { needsEventToken = eventToken }
                val controller = controller(selector)
                val reading = controller.readMode()
                assertNull(reading.mode)
                assertTrue(reading.detail.contains("Invalid headlight selector reading: $mode"))
                assertFalse(controller.setMode(1).success)
                assertTrue(selector.writes.isEmpty())
            }
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

    @Test fun sentinelOrRejectedPrimitiveReadUsesEventValueTokenAndRestoresOriginalSelector() {
        for (rejectPrimitive in listOf(false, true)) {
            val selector = LegacySelector().apply {
                needsEventToken = true
                rejectPrimitiveToken = rejectPrimitive
            }
            val controller = controller(selector)
            val saved = controller.readMode()
            assertEquals(2, saved.mode)
            assertEquals(listOf(Int::class.java, BYDAutoEventValue::class.java), selector.readTypes)
            assertTrue(saved.detail.contains("BYDAutoEventValue"))
            assertTrue(controller.setMode(1).success)
            assertEquals(1, controller.mode())
            assertTrue(controller.setMode(saved.mode!!).success)
            assertEquals(2, controller.mode())
        }
    }

    @Test fun runtimeSdkFeatureIdsOverrideCompatibilityIdsForReadsAndWrites() {
        val feedback = Instrument.INSTRUMENT_HEADLIGHT_CONTROL_FEEDBACK
        val command = Instrument.INSTRUMENT_HEADLIGHT_CONTROL_SET
        try {
            Instrument.INSTRUMENT_HEADLIGHT_CONTROL_FEEDBACK = 12345
            Instrument.INSTRUMENT_HEADLIGHT_CONTROL_SET = 67890
            val selector = LegacySelector().apply { expectedFeedbackId = 12345; expectedSetId = 67890 }
            val controller = controller(selector)
            assertEquals(2, controller.mode())
            assertTrue(controller.setMode(1).success)
            assertEquals(1, controller.mode())
            Instrument.INSTRUMENT_HEADLIGHT_CONTROL_SET = 0
            assertFalse(controller.setMode(2).success)
            assertEquals(listOf(1), selector.writes)
            val reads = selector.readTypes.size
            Instrument.INSTRUMENT_HEADLIGHT_CONTROL_FEEDBACK = 0
            assertNull(controller.mode())
            assertEquals("Invalid SDK IDs must not be sent to the device", reads, selector.readTypes.size)
        } finally {
            Instrument.INSTRUMENT_HEADLIGHT_CONTROL_FEEDBACK = feedback
            Instrument.INSTRUMENT_HEADLIGHT_CONTROL_SET = command
        }
    }

    @Test fun coldSdkGetsTimeToReturnRealFeedbackWithoutIssuingAnyCommand() = runBlocking {
        val selector = WarmingSelector()
        val reading = controller(selector).awaitMode()
        assertEquals(2, reading.mode)
        assertEquals(3, selector.reads)
    }

    @Test fun reportedZeroAndUnavailableSentinelRemainFailuresAfterReadinessWait() = runBlocking {
        val selector = RejectedNamedSelector().apply { current = -999999999 }
        val reading = controller(selector).awaitMode()
        assertNull(reading.mode)
        assertTrue(reading.detail.contains("after waiting 1.5s"))
        assertTrue(reading.detail.contains("getHeadlightControlMode: Invalid headlight selector reading: 0"))
        assertTrue(reading.detail.contains("Instrument.get(1011875880, int): Invalid headlight selector reading: -999999999"))
        assertTrue(reading.detail.contains("Instrument.get(1011875880, BYDAutoEventValue)"))
        assertTrue(selector.writes.isEmpty())
    }

    @Test fun explicitDebugTestsCanTryOneOffCommandWithUnreadableSelectorButConfirmedPark() {
        val named = Selector(0)
        val feature = LegacySelector(0)
        assertNull(controller(named).mode())
        assertNull(controller(feature).mode())
        val namedResult = controller(named).testOff(BydHeadlightController.TestPath.NAMED,
            { BydVehicleInfoController.GearReads(1, null) }, { true })
        val featureResult = controller(feature).testOff(BydHeadlightController.TestPath.FEATURE,
            { BydVehicleInfoController.GearReads(null, 1) }, { true })
        assertTrue(namedResult.detail, namedResult.success)
        assertTrue(featureResult.detail, featureResult.success)
        assertEquals(listOf(1), named.writes)
        assertEquals(listOf(1), feature.writes)
    }

    @Test fun debugOffTestsNeverWriteWithoutForegroundAndUnambiguousPark() {
        val invalidGears = listOf(null, BydVehicleInfoController.GearReads(null, null),
            BydVehicleInfoController.GearReads(0, 0), BydVehicleInfoController.GearReads(2, null),
            BydVehicleInfoController.GearReads(1, 2), BydVehicleInfoController.GearReads(3, 1))
        for (path in BydHeadlightController.TestPath.entries) {
            val named = Selector(0)
            val feature = LegacySelector(0)
            val controller = controller(if (path == BydHeadlightController.TestPath.NAMED) named else feature)
            for (gears in invalidGears) {
                val result = controller.testOff(path, { gears }, { true })
                assertFalse(result.success)
                assertTrue(result.detail.contains("Park could not be confirmed"))
            }
            val background = controller.testOff(path, { BydVehicleInfoController.GearReads(1, 1) }, { false })
            assertFalse(background.success)
            assertTrue(background.detail.contains("lost foreground"))
            assertTrue(named.writes.isEmpty())
            assertTrue(feature.writes.isEmpty())
        }
    }

    @Test fun debugNamedFailureNeverFallsBackButAnExplicitFeatureTestUsesOnlyThatPath() {
        val selector = RejectedNamedSelector().apply { throwOnWrite = true; current = 0 }
        val controller = controller(selector)
        val named = controller.testOff(BydHeadlightController.TestPath.NAMED,
            { BydVehicleInfoController.GearReads(1, null) }, { true })
        assertFalse(named.success)
        assertTrue(named.detail.contains("instrument write denied"))
        assertTrue(selector.writes.isEmpty())
        val feature = controller.testOff(BydHeadlightController.TestPath.FEATURE,
            { BydVehicleInfoController.GearReads(1, null) }, { true })
        assertTrue(feature.detail, feature.success)
        assertEquals(listOf(1), selector.writes)
    }
}
