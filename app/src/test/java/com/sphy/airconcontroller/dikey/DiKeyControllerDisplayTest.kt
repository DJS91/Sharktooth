package com.sphy.airconcontroller.dikey

import org.junit.Assert.*
import org.junit.Test

class DiKeyControllerDisplayTest {
    @Test fun displaysFollowSuccessfulWritesAndClearOnDisconnect() {
        val controller = readyController()
        val leftType = DialDisplayType.PASSENGER_TEMP.code
        val rightType = DialDisplayType.DRIVER_FAN.code
        assertEquals(DialDisplays(), controller.dialDisplays.value)

        assertTrue(controller.sendDialDisplay(true, leftType, 23))
        assertNull(controller.dialDisplays.value.left)
        controller.onWriteComplete(false)
        assertNull(controller.dialDisplays.value.left)

        controller.sendDialDisplay(true, leftType, 24)
        controller.onWriteComplete(true)
        assertEquals(DialDisplay(leftType, 24), controller.dialDisplays.value.left)

        controller.sendDialDisplay(false, rightType, 3)
        controller.onWriteComplete(true)
        controller.onWriteComplete(true)
        assertEquals(DialDisplay(rightType, 3), controller.dialDisplays.value.right)
        assertEquals(DialDisplay(leftType, 24), controller.dialDisplays.value.left)

        controller.sendDialDisplay(true, DiKeyProtocol.DISPLAY_TYPE_BLANK, 0)
        controller.ingestNotify(encoder(left = true, leftType, 25))
        assertEquals(DialDisplay(leftType, 24), controller.dialDisplays.value.left)
        controller.onWriteComplete(true)
        controller.onWriteComplete(true)
        assertEquals(DialDisplay(DiKeyProtocol.DISPLAY_TYPE_BLANK, 0), controller.dialDisplays.value.left)
        controller.ingestNotify(encoder(left = true, leftType, 25))
        assertEquals(DialDisplay(DiKeyProtocol.DISPLAY_TYPE_BLANK, 0), controller.dialDisplays.value.left)

        controller.onTransportDown("Disconnected")
        assertEquals(DialDisplays(), controller.dialDisplays.value)
    }

    @Test fun newerDialNotificationIsNotOverwrittenByAnEarlierWriteCompletion() {
        val controller = readyController()
        val type = DialDisplayType.PASSENGER_TEMP.code
        controller.sendDialDisplay(true, type, 22)
        controller.ingestNotify(encoder(left = true, type, 25))
        assertEquals(DialDisplay(type, 25), controller.dialDisplays.value.left)
        controller.onWriteComplete(true)
        assertEquals(DialDisplay(type, 25), controller.dialDisplays.value.left)

        controller.ingestNotify(encoder(left = false, DialDisplayType.DRIVER_FAN.code, 4))
        assertEquals(DialDisplay(DialDisplayType.DRIVER_FAN.code, 4), controller.dialDisplays.value.right)
        assertEquals(DialDisplay(type, 25), controller.dialDisplays.value.left)
    }

    private fun readyController(): DiKeyController {
        val controller = DiKeyController(onStatus = {}, onEvent = {})
        controller.attachSink(object : DiKeyFrameSink {
            override fun writeFrame(bytes: ByteArray) = true
        })
        controller.onTransportReady()
        repeat(3) { controller.onWriteComplete(true) }
        return controller
    }

    private fun encoder(left: Boolean, type: Int, value: Int): ByteArray {
        val frame = DiKeyProtocol.buildFrame(
            DiKeyProtocol.CMD_ENCODER_EVENT,
            byteArrayOf(if (left) 0x21 else 0x20, 0x03, type.toByte(), value.toByte()),
        )
        frame[1] = 0x66
        frame[frame.lastIndex] = (frame.dropLast(1).sumOf { it.toInt() and 0xff } and 0xff).toByte()
        return frame
    }
}
