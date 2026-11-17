package com.sphy.airconcontroller.camp

import android.bluetooth.BluetoothAdapter
import com.sphy.airconcontroller.adb.AdbPermissionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CampHeadunitBluetoothTest {
    @Test fun togglesOnlyAndroidsAdapterAndAlwaysEnablesItOnExit() = runBlocking {
        var state = BluetoothAdapter.STATE_ON
        val commands = mutableListOf<String>()
        val bluetooth = CampHeadunitBluetooth({ state }) { command ->
            commands += command
            state = if (command.endsWith("disable")) BluetoothAdapter.STATE_OFF else BluetoothAdapter.STATE_ON
            AdbPermissionManager.ShellResult(0, "")
        }
        bluetooth.prepare()
        assertTrue(bluetooth.setEnabled(false).success)
        assertTrue(bluetooth.setEnabled(true).success)
        assertEquals(listOf("svc bluetooth disable", "svc bluetooth enable"), commands)
        state = BluetoothAdapter.STATE_OFF
        assertTrue(bluetooth.setEnabled(false).success)
        assertEquals(2, commands.size)
        assertTrue(bluetooth.setEnabled(true).success)
        assertEquals("svc bluetooth enable", commands.last())
        assertEquals(BluetoothAdapter.STATE_ON, state)
    }

    @Test fun rejectedBluetoothWriteIsReportedInsteadOfBeingCheckedOff() = runBlocking {
        val bluetooth = CampHeadunitBluetooth({ BluetoothAdapter.STATE_ON }) {
            AdbPermissionManager.ShellResult(1, "Permission denied")
        }
        val result = bluetooth.setEnabled(false)
        assertFalse(result.success)
        assertTrue(result.detail.contains("Permission denied"))
    }

    @Test fun waitsForAndroidReadbackRatherThanTreatingShellSuccessAsCompletion() = runBlocking {
        var requested = false
        var reads = 0
        val bluetooth = CampHeadunitBluetooth({
            if (requested && ++reads >= 3) BluetoothAdapter.STATE_OFF else BluetoothAdapter.STATE_TURNING_OFF
        }) {
            requested = true
            AdbPermissionManager.ShellResult(0, "")
        }
        assertTrue(bluetooth.setEnabled(false).success)
        assertTrue(reads >= 3)
    }
}
