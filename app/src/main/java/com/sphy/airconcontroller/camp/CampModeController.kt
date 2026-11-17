package com.sphy.airconcontroller.camp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.byd.BydDisplayController
import com.sphy.airconcontroller.byd.BydDrlController
import com.sphy.airconcontroller.byd.BydHeadlightController
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.byd.BydWindowController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException

/** Application-scoped so navigating away or the car blanking the display cannot cancel a step. */
internal class CampModeController private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("camp-mode", Context.MODE_PRIVATE)
    private val actions = VehicleActions()
    private val saved = prefs.getString("saved", null)?.let { decodeSaved(JSONObject(it)) }
    private val session = CampModeSession(actions, saved, prefs.getString("report", "").orEmpty(),
        prefs.getStringSet("completedSteps", emptySet()).orEmpty().map { CampStep.valueOf(it) }.toSet())
    val state = session.state

    init {
        ContextCompat.registerReceiver(app, CampScreenWakeReceiver { onScreenWake() },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            state.map { it.awaitingWake }.distinctUntilChanged().collectLatest { awaitingWake ->
                if (!awaitingWake) return@collectLatest
                val tracker = CampBacklightWakeTracker(state.value.screenOffStartedAtMillis,
                    CampStep.SCREEN in state.value.completedSteps)
                var since = state.value.screenOffStartedAtMillis
                while (state.value.awaitingWake) {
                    val readStartedAt = System.currentTimeMillis()
                    val result = AdbPermissionManager.runShellCommand(app, wakeLogCommand(since))
                    if (result.exitCode != 0) {
                        Log.w("CampModeController", "BYD wake log unavailable: ${result.output}; Android wake and touch detection remain active.")
                        return@collectLatest
                    }
                    if (tracker.woke(result.output)) {
                        onScreenWake()
                        return@collectLatest
                    }
                    since = readStartedAt
                    delay(1000)
                }
            }
        }
    }

    fun enter() { scope.launch { session.enter() } }
    fun exit() { scope.launch { session.exit() } }
    fun stopWindows() { scope.launch { session.stopWindows() } }

    fun onScreenWake(): Boolean {
        if (!state.value.awaitingWake) return false
        scope.launch { session.onScreenWake() }
        return true
    }

    private inner class VehicleActions : CampActions {
        private val ac = BydAcController(app)
        private val windows = BydWindowController(app)
        private val drl = BydDrlController(app)
        private val headlights = BydHeadlightController(app)
        private val display = BydDisplayController(app)
        private val vehicle = BydVehicleInfoController(app)
        private val audio = CampMediaAudio(app)
        private val windowCommands = Mutex()
        private val persistence = Mutex()

        override suspend fun save(): CampSavedState = io {
            requirePark()
            val climate = ac.snapshot()
            check(climate.bound && climate.powerOn != null) { "Cannot read the previous climate state; nothing changed." }
            val drlOn = checkNotNull(drl.isOn()) { "Cannot read the previous DRL state; nothing changed." }
            val headlightMode = checkNotNull(headlights.mode()) {
                "Cannot read the previous headlight selector; nothing changed."
            }
            val media = audio.snapshot()
            val wakeLog = AdbPermissionManager.runShellCommandQuick(app, wakeLogCommand(System.currentTimeMillis()))
            check(wakeLog.exitCode == 0) {
                "Cannot monitor the car's backlight wake event: ${wakeLog.output}. Check local ADB; nothing changed."
            }
            CampSavedState(climate, media.volume, media.muted, drlOn, headlightMode)
        }

        private fun requirePark() {
            val gears = vehicle.readGear()
            check(gears != null && listOfNotNull(gears.gear, gears.autoMode).any { it == 1 } &&
                listOfNotNull(gears.gear, gears.autoMode).none { it in 2..6 }) {
                "Park could not be confirmed ($gears). Select Park before enabling Camp Mode."
            }
        }

        override suspend fun climateOff(): CampResult {
            val result = io { ac.stop() }
            delay(1500)
            val off = io { ac.snapshot().powerOn == false }
            return CampResult(result.success && off, "${result.detail}; climate OFF read-back=$off")
        }

        override suspend fun closeWindows(): CampResult {
            val result = windowCommands.withLock { io { windows.closeAll() } }
            if (!result.success) return CampResult(false, result.detail)
            var positions = io { windows.positions() }
            repeat(10) {
                if (positions.values.all { it == 0 }) return CampResult(true, "All four windows report closed.")
                delay(1000)
                positions = io { windows.positions() }
            }
            return CampResult(false, "Window read-back: $positions")
        }

        override suspend fun drlOff(): CampResult = setDrl(false)

        override suspend fun headlightsOff(): CampResult = setHeadlights(BydHeadlightController.OFF)

        override suspend fun muteMedia(): CampResult = io { audio.mute() }

        override suspend fun screenOff(): CampResult {
            val direct = display.startCarScreenSaver()
            if (direct.success) return CampResult(true, direct.detail)
            val shell = AdbPermissionManager.runShellCommand(app, BydDisplayController.SCREEN_SAVER_SHELL)
            return CampResult(shell.exitCode == 0 && shell.output.contains("Starting service") &&
                !shell.output.contains("Error", ignoreCase = true),
                "${direct.detail}; ADB: ${shell.output.ifBlank { "exit ${shell.exitCode}" }}")
        }

        override suspend fun stopWindows(): CampResult = windowCommands.withLock {
            io {
                val result = windows.stopAll()
                CampResult(result.success, result.detail)
            }
        }

        override suspend fun restoreClimate(saved: CampSavedState): CampResult = io {
            val results = ac.restoreSnapshot(saved.climate)
            val after = ac.snapshot()
            CampResult(results.all { it.success } && after.powerOn == saved.climate.powerOn,
                results.joinToString("\n") { "${it.method}: ${it.success} ${it.detail}" } +
                    "\nClimate power read-back=${after.powerOn}")
        }

        override suspend fun restoreMedia(saved: CampSavedState): CampResult =
            io { audio.restore(saved.mediaVolume, saved.mediaMuted) }

        override suspend fun restoreDrl(saved: CampSavedState): CampResult = setDrl(saved.drlOn)

        override suspend fun restoreHeadlights(saved: CampSavedState): CampResult {
            val mode = saved.headlightMode
                ?: return CampResult(true, "Older session did not change headlights; no restoration needed.")
            return setHeadlights(mode)
        }

        private suspend fun setHeadlights(mode: Int): CampResult {
            val before = io { headlights.mode() }
                ?: return CampResult(false, "Headlight selector read-back unavailable.")
            if (before == mode) return CampResult(true, "Headlight selector already at mode $mode; no command sent.")
            val result = io {
                if (mode == BydHeadlightController.OFF) requirePark()
                headlights.setMode(mode)
            }
            if (!result.success) return CampResult(false, result.detail)
            repeat(10) {
                delay(150)
                if (io { headlights.mode() } == mode) return CampResult(true, "Headlight selector mode $mode confirmed.")
            }
            return CampResult(false, "${result.detail}; headlight selector read-back=${io { headlights.mode() }}")
        }

        private suspend fun setDrl(on: Boolean): CampResult {
            val result = io { drl.set(on) }
            repeat(10) {
                delay(150)
                if (io { drl.isOn() } == on) return CampResult(result.success, "DRLs ${if (on) "ON" else "OFF"} confirmed.")
            }

            return CampResult(false, "${result.detail}; DRL read-back=${io { drl.isOn() }}")
        }

        override suspend fun persist(saved: CampSavedState?, report: String, completedSteps: Set<CampStep>) {
            persistence.withLock {
                io {
                    val editor = prefs.edit().putString("report", report)
                        .putStringSet("completedSteps", completedSteps.map { it.name }.toSet())
                    if (saved == null) editor.remove("saved") else editor.putString("saved", encodeSaved(saved).toString())
                    if (!editor.commit()) throw IOException("Cannot save Camp Mode restoration data.")
                }
            }
        }
    }

    companion object {
        private fun wakeLogCommand(sinceMillis: Long): String {
            val timestamp = "${sinceMillis / 1000}.${(sinceMillis % 1000).toString().padStart(3, '0')}"
            return "logcat -d -v epoch -b main,system -T '$timestamp' -s TsCarPower:W '*:S'"
        }
        @Volatile private var instance: CampModeController? = null
        fun get(context: Context): CampModeController = instance ?: synchronized(this) {
            instance ?: CampModeController(context).also { instance = it }
        }

        private suspend fun <T> io(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

        internal fun encodeSaved(saved: CampSavedState): JSONObject {
            val ac = saved.climate
            return JSONObject().put("volume", saved.mediaVolume).put("muted", saved.mediaMuted)
                .put("drl", saved.drlOn).put("headlightMode", saved.headlightMode).put("climate", JSONObject().apply {
                    put("power", ac.powerOn)
                    put("driver", ac.driverTempC)
                    put("passenger", ac.passengerTempC)
                    put("fan", ac.fanLevel)
                    put("control", ac.controlMode)
                    put("cycle", ac.cycleMode)
                    put("wind", ac.windMode)
                    put("compressor", ac.compressorMode)
                    put("maxCool", ac.maxCool)
                    put("ventilation", ac.ventilation)
                    put("frontDefrost", ac.frontDefrost)
                    put("rearDefrost", ac.rearDefrost)
                    put("tempSynced", ac.tempSynced)
                })
        }

        internal fun decodeSaved(json: JSONObject): CampSavedState {
            val ac = json.getJSONObject("climate")
            fun int(key: String) = if (ac.isNull(key)) null else ac.getInt(key)
            fun bool(key: String) = if (ac.isNull(key)) null else ac.getBoolean(key)
            return CampSavedState(
                BydAcController.AcSnapshot(
                    sdkInjected = true, bound = true, bindError = null,
                    powerOn = bool("power"), driverTempC = int("driver"), passengerTempC = int("passenger"),
                    outsideTempC = null, fanLevel = int("fan"), controlMode = int("control"),
                    cycleMode = int("cycle"), windMode = int("wind"), compressorMode = int("compressor"),
                    maxCool = bool("maxCool"), ventilation = bool("ventilation"),
                    frontDefrost = bool("frontDefrost"), rearDefrost = bool("rearDefrost"), tempSynced = bool("tempSynced"),
                ),
                json.getInt("volume"), json.getBoolean("muted"), json.getBoolean("drl"),
                if (json.isNull("headlightMode")) null else json.getInt("headlightMode"),
            )
        }
    }
}

internal class CampScreenWakeReceiver(private val onWake: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_USER_PRESENT) onWake()
    }
}

internal class CampMediaAudio(context: Context) {
    data class Saved(val volume: Int, val muted: Boolean)
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    fun snapshot(): Saved {
        check(!audio.isVolumeFixed) { "Android reports fixed media volume; muting is not supported." }
        return Saved(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    fun mute(): CampResult {
        // Only STREAM_MUSIC: do not mute master audio, calls, alarms or safety streams.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return CampResult(level == 0, "Media volume=$level")
    }

    fun restore(volume: Int, muted: Boolean): CampResult {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        val after = snapshot()
        return CampResult(after.volume == volume && after.muted == muted, "Media volume=${after.volume} muted=${after.muted}")
    }
}
