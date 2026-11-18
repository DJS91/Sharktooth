package com.sphy.airconcontroller.camp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CampCarPlayTest {
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
}
