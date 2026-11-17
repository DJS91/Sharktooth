package com.sphy.airconcontroller.dikey

import com.sphy.airconcontroller.storage.LedBarState
import com.sphy.airconcontroller.storage.LedRestoreSnapshot
import com.sphy.airconcontroller.storage.LedRgb
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiKeyControllerLightingTest {
    private val saved = LedRestoreSnapshot((1..3).associateWith {
        LedBarState(LedMode.ON.code, LedRgb(12 * it, 24 * it, 36 * it))
    }, LedRgb(8, 16, 32))

    private class Link {
        val frames = mutableListOf<ByteArray>()
        var reject = false
        private var completed = 0
        val controller = DiKeyController(onStatus = {}, onEvent = {})
        init {
            controller.attachSink(object : DiKeyFrameSink {
                override fun writeFrame(bytes: ByteArray): Boolean {
                    if (reject) return false
                    frames += bytes
                    return true
                }
            })
            controller.onTransportReady()
            drain()
            clear()
        }
        fun acknowledge() {
            completed++
            controller.onWriteComplete(true)
        }
        fun drain() {
            while (completed < frames.size) acknowledge()
        }
        fun clear() { frames.clear(); completed = 0 }
    }

    @Test fun campWaitsForWritesAndKeepsAutomaticLightingDarkWithoutLosingColours() = runBlocking {
        val link = Link()
        val off = async(start = CoroutineStart.UNDISPATCHED) { link.controller.applyCampLighting(false, saved) }
        repeat(3) {
            assertFalse("Queued writes are not acknowledged yet", off.isCompleted)
            link.acknowledge()
        }
        assertFalse(off.isCompleted)
        link.acknowledge()
        assertTrue(off.await())
        assertEquals(4, link.frames.size)
        for ((index, state) in saved.bars.values.withIndex()) {
            assertArrayEquals(DiKeyProtocol.buildLedStrip(LedMode.OFF.code, index + 1,
                state.color.red, state.color.green, state.color.blue), link.frames[index])
        }
        assertArrayEquals(DiKeyProtocol.buildButtonBacklight(0, 0, 0), link.frames.last())

        link.clear()
        link.controller.sendLed(LedMode.ON.code, 4, 255, 255, 255)
        link.controller.sendButtonBacklight(255, 255, 255)
        link.drain()
        assertArrayEquals(DiKeyProtocol.buildLedStrip(LedMode.OFF.code, 4, 255, 255, 255), link.frames[0])
        assertArrayEquals(DiKeyProtocol.buildButtonBacklight(0, 0, 0), link.frames[1])

        link.clear()
        val on = async(start = CoroutineStart.UNDISPATCHED) { link.controller.applyCampLighting(true, saved) }
        link.drain()
        assertTrue(on.await())
        for ((index, state) in saved.bars.values.withIndex()) {
            assertArrayEquals(DiKeyProtocol.buildLedStrip(state.mode, index + 1,
                state.color.red, state.color.green, state.color.blue), link.frames[index])
        }
        assertArrayEquals(DiKeyProtocol.buildButtonBacklight(8, 16, 32), link.frames.last())
    }

    @Test fun disconnectAndRejectedWritesNeverCountAsSuccessfulLightingChanges() = runBlocking {
        val link = Link()
        val off = async(start = CoroutineStart.UNDISPATCHED) { link.controller.applyCampLighting(false, saved) }
        link.controller.onTransportDown("Disconnected")
        assertFalse(off.await())
        link.clear()
        link.controller.onTransportReady()
        link.drain()
        val ledFrames = link.frames.filter { it[3] == DiKeyProtocol.CMD_LED_STRIP }
        assertEquals(3, ledFrames.size)
        assertTrue("Reconnect must not relight a camping bar", ledFrames.all { it[4].toInt() == LedMode.OFF.code })
        link.reject = true
        assertFalse(link.controller.applyCampLighting(true, saved))
    }

    @Test fun exitTurnsPreviouslyOffStripModesOnWhilePreservingColours() = runBlocking {
        val link = Link()
        val previous = saved.copy(bars = saved.bars.mapValues { (_, state) -> state.copy(mode = LedMode.OFF.code) })
        val on = async(start = CoroutineStart.UNDISPATCHED) { link.controller.applyCampLighting(true, previous) }
        link.drain()
        assertTrue(on.await())
        assertTrue(link.frames.filter { it[3] == DiKeyProtocol.CMD_LED_STRIP }.all {
            it[4].toInt() == LedMode.ON.code
        })
        link.clear()
        link.controller.onTransportDown("Disconnected")
        link.controller.onTransportReady()
        link.drain()
        assertTrue("Reconnect should retain the re-enabled look",
            link.frames.filter { it[3] == DiKeyProtocol.CMD_LED_STRIP }.all { it[4].toInt() == LedMode.ON.code })
    }
}
