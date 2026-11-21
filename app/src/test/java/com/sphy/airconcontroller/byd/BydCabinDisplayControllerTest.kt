package com.sphy.airconcontroller.byd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BydCabinDisplayControllerTest {
    class Instrument(var brightness: Int = 7, private val applies: Boolean = true) {
        val writes = mutableListOf<Int>()
        fun getBacklightBrightness() = brightness
        fun setBacklightBrightness(value: Int): Int {
            writes += value
            if (applies) brightness = value
            return 0
        }
    }

    class Setting(var hud: Int = 1, private val applies: Boolean = true) {
        val writes = mutableListOf<Int>()
        fun getHudState() = hud
        fun setHudState(value: Int): Int {
            writes += value
            if (applies) hud = value
            return 0
        }
    }

    private fun controller(instrument: Instrument?, setting: Setting?) =
        BydCabinDisplayController({ instrument }, { setting }) { _, _ -> }

    @Test fun dimmingAndHudOffRestoreTheirActualOriginalValues() = runBlocking {
        val instrument = Instrument()
        val setting = Setting()
        val displays = controller(instrument, setting)
        val brightness = displays.readClusterBrightness().value!!
        val hud = displays.readHudState().value!!
        var guarded = 0
        assertTrue(displays.setClusterBrightness(1) { guarded++ }.success)
        assertTrue(displays.setHudState(2) { guarded++ }.success)
        assertEquals(1, instrument.brightness)
        assertEquals(2, setting.hud)
        assertTrue(displays.setClusterBrightness(brightness) { guarded++ }.success)
        assertTrue(displays.setHudState(hud) { guarded++ }.success)
        assertEquals(listOf(1, 7), instrument.writes)
        assertEquals(listOf(2, 1), setting.writes)
        assertEquals(4, guarded)
    }

    @Test fun acceptedButIgnoredCommandsAreNotReportedAsSuccess() = runBlocking {
        val displays = controller(Instrument(applies = false), Setting(applies = false))
        assertFalse(displays.setClusterBrightness(1) {}.success)
        assertFalse(displays.setHudState(2) {}.success)
    }

    @Test fun invalidOrUnavailableReadingsPreventWrites() = runBlocking {
        val instrument = Instrument(brightness = 0)
        val setting = Setting(hud = -999999999)
        val displays = controller(instrument, setting)
        assertNull(displays.readClusterBrightness().value)
        assertNull(displays.readHudState().value)
        assertFalse(displays.setClusterBrightness(1) { fail("Must not reach write guard") }.success)
        assertFalse(displays.setHudState(2) { fail("Must not reach write guard") }.success)
        assertTrue(instrument.writes.isEmpty())
        assertTrue(setting.writes.isEmpty())
        assertFalse(controller(null, null).setHudState(2) {}.success)
    }

    @Test fun parkGuardRunsAfterBindingAndImmediatelyBeforeAnyWrite() = runBlocking {
        val instrument = Instrument()
        val setting = Setting()
        val displays = controller(instrument, setting)
        val guard = { throw IllegalStateException("Park no longer confirmed") }
        val cluster = displays.setClusterBrightness(1, guard)
        val hud = displays.setHudState(2, guard)
        assertFalse(cluster.success)
        assertFalse(hud.success)
        assertTrue(hud.detail.contains("Park no longer confirmed"))
        assertTrue(instrument.writes.isEmpty())
        assertTrue(setting.writes.isEmpty())
    }

    @Test fun alreadyDimClusterAndAlreadyOffHudNeedNoWrites() = runBlocking {
        val instrument = Instrument(brightness = 1)
        val setting = Setting(hud = 2)
        val displays = controller(instrument, setting)
        assertTrue(displays.setClusterBrightness(1) { fail("No write needed") }.success)
        assertTrue(displays.setHudState(2) { fail("No write needed") }.success)
        assertTrue(instrument.writes.isEmpty())
        assertTrue(setting.writes.isEmpty())
    }

    @Test fun restorationWritesSavedOrFallbackBrightnessFromAnyCurrentReading() = runBlocking {
        for (current in listOf(1, 7, 22, 0, -999999999)) {
            val instrument = Instrument(brightness = current)
            val displays = controller(instrument, Setting())
            var guarded = 0
            assertTrue(displays.restoreClusterBrightness(16) { guarded++ }.success)
            assertEquals(16, instrument.brightness)
            assertEquals(listOf(16), instrument.writes)
            assertEquals(1, guarded)
        }
        val instrument = Instrument(brightness = 3)
        val fallback = controller(instrument, Setting()).restoreClusterBrightness(null) {}
        assertTrue(fallback.success)
        assertEquals(14, instrument.brightness)
        assertTrue(fallback.detail.contains("fallback 14"))
    }

    @Test fun restorationStillChecksParkAndReadbackWhenCurrentBrightnessIsUnknown() = runBlocking {
        val instrument = Instrument(brightness = 0)
        val displays = controller(instrument, Setting())
        val rejected = displays.restoreClusterBrightness(null) { error("Park not confirmed") }
        assertFalse(rejected.success)
        assertTrue(instrument.writes.isEmpty())
        val ignored = Instrument(brightness = 0, applies = false)
        assertFalse(controller(ignored, Setting()).restoreClusterBrightness(null) {}.success)
        assertEquals(listOf(14), ignored.writes)
    }
}
