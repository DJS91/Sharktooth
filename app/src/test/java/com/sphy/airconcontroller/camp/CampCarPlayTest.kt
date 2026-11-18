package com.sphy.airconcontroller.camp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CampCarPlayTest {
    class Device(
        private val id: String? = null,
        private val bluetooth: String? = null,
        private val usb: String? = null,
    ) {
        fun getDeviceId() = id
        fun getBtAddr() = bluetooth
        fun getUsbSerialNumber() = usb
    }

    private class Client(var status: Int) : CampCarPlay.Client {
        var ids = listOf("connected-phone")
        var applies = true
        val disconnected = mutableListOf<String>()
        var closed = false
        override fun state() = status
        override fun activeDeviceIds() = ids
        override fun disconnect(deviceId: String) {
            disconnected += deviceId
            if (applies) status = 4
        }
        override fun close() { closed = true }
    }

    @Test fun activeAndActivatingSessionsDisconnectTheActualDeviceAndConfirmCompletion() = runBlocking {
        for (state in listOf(1, 2)) {
            val client = Client(state)
            assertTrue(CampCarPlay { client }.disconnectIfConnected().success)
            assertEquals(listOf("connected-phone"), client.disconnected)
            assertTrue(client.closed)
        }
    }

    @Test fun absentOrDisconnectedCarPlayDoesNotSendACommand() = runBlocking {
        assertTrue(CampCarPlay { null }.disconnectIfConnected().success)
        for (state in listOf(0, 4, 5)) {
            val client = Client(state)
            assertTrue(CampCarPlay { client }.disconnectIfConnected().success)
            assertTrue(client.disconnected.isEmpty())
            assertTrue(client.closed)
        }
    }

    @Test fun unreadableDeviceOrUnknownSessionCannotBeTreatedAsDisconnected() = runBlocking {
        for (client in listOf(Client(-1), Client(2).apply { ids = emptyList() })) {
            try {
                CampCarPlay { client }.disconnectIfConnected()
                fail("Invalid CarPlay state must fail visibly")
            } catch (_: IllegalStateException) {
                assertTrue(client.disconnected.isEmpty())
                assertTrue(client.closed)
            }
        }
    }

    @Test fun ignoredDisconnectFailsConfirmationAndDoesNotKeepSendingCommands() = runBlocking {
        val client = Client(2).apply { applies = false }
        assertFalse(CampCarPlay { client }.disconnectIfConnected().success)
        assertEquals(listOf("connected-phone"), client.disconnected)
        assertTrue(client.closed)
    }

    @Test fun validCarPlayIdDoesNotRequireConnectionManagerFallback() {
        assertEquals(listOf("actual-id"), CampCarPlay.resolveDeviceIds(listOf(Device(id = "actual-id"))) {
            error("A readable CarPlay ID needs no additional lookup")
        })
    }

    @Test fun missingWirelessIdUsesActualMatchingConnectionIdNotItsBluetoothAddress() = runBlocking {
        val ids = CampCarPlay.resolveDeviceIds(listOf(Device(bluetooth = "AA:BB:CC:DD:EE:01"))) {
            listOf(
                Device(id = "unrelated-id", bluetooth = "AA:BB:CC:DD:EE:02"),
                Device(id = "actual-connection-id", bluetooth = "aa:bb:cc:dd:ee:01"),
            )
        }
        val client = Client(2).apply { this.ids = ids }
        assertTrue(CampCarPlay { client }.disconnectIfConnected().success)
        assertEquals(listOf("actual-connection-id"), client.disconnected)
        assertTrue(client.closed)
    }

    @Test fun missingWiredIdMatchesActualUsbIdentityAndDeduplicatesConnections() {
        val wired = Device(usb = "connected-usb")
        assertEquals(listOf("actual-usb-id"), CampCarPlay.resolveDeviceIds(listOf(wired, wired)) {
            listOf(Device(id = "actual-usb-id", usb = "connected-usb"))
        })
    }

    @Test fun absentAmbiguousOrUnidentifiedMatchesFailRatherThanGuessingAnId() {
        val active = Device(bluetooth = "connected", usb = "same-usb")
        val alternatives = listOf(
            emptyList(),
            listOf(Device(id = "wrong-phone", bluetooth = "different", usb = "same-usb")),
            listOf(Device(bluetooth = "connected")),
            listOf(Device(id = "one", bluetooth = "connected"), Device(id = "two", bluetooth = "connected")),
        )
        for (connections in alternatives) {
            assertThrows(IllegalStateException::class.java) {
                CampCarPlay.resolveDeviceIds(listOf(active)) { connections }
            }
        }
        assertThrows(IllegalStateException::class.java) {
            CampCarPlay.resolveDeviceIds(listOf(Device(id = " "))) {
                error("No transport identity must prevent a lookup")
            }
        }
    }
}
