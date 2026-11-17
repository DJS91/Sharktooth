package com.sphy.airconcontroller.camp

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.sphy.airconcontroller.adb.AdbPermissionManager
import kotlinx.coroutines.delay

/** Controls only Android's local adapter; never sends a Bluetooth command to the ESP32. */
internal class CampHeadunitBluetooth(
    private val readState: () -> Int,
    private val shell: suspend (String) -> AdbPermissionManager.ShellResult,
) {
    constructor(context: Context) : this(
        readState = {
            checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter) {
                "Head-unit Bluetooth adapter unavailable."
            }.state
        },
        shell = { AdbPermissionManager.runShellCommand(context, it) },
    )

    fun prepare() {
        check(readState() in setOf(BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_ON,
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_TURNING_ON)) {
            "Cannot read the head-unit Bluetooth adapter state."
        }
    }

    suspend fun setEnabled(enabled: Boolean): CampResult {
        val target = if (enabled) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF
        val label = "Head-unit Bluetooth ${if (enabled) "ON" else "OFF"}"
        if (readState() == target) return CampResult(true, "$label already confirmed.")
        val command = shell("svc bluetooth ${if (enabled) "enable" else "disable"}")
        if (command.exitCode != 0) return CampResult(false, "$label: ${command.output}")
        repeat(40) {
            if (readState() == target) return CampResult(true, "$label confirmed by Android adapter state.")
            delay(250)
        }
        return CampResult(false, "$label not confirmed; adapter state=${readState()}; ${command.output}")
    }
}
