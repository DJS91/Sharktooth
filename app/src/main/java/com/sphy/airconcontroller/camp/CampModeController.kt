package com.sphy.airconcontroller.camp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.OpenDiKeyApp
import com.sphy.airconcontroller.R
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.byd.BydCabinDisplayController
import com.sphy.airconcontroller.byd.BydDisplayController
import com.sphy.airconcontroller.byd.BydDrlController
import com.sphy.airconcontroller.byd.BydSaveSocController
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.byd.BydWindowController
import com.sphy.airconcontroller.dikey.LedMode
import com.sphy.airconcontroller.storage.LedRestoreSnapshot
import com.sphy.airconcontroller.storage.LightingProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
        CampStep.decodeSaved(prefs.getStringSet("completedSteps", emptySet()).orEmpty()),
        prefs.getBoolean("remindHeadlights", false),
        prefs.getString("failedSteps", null)?.let { decodeErrors(JSONObject(it)) }.orEmpty(),
        prefs.getString("sessionError", null))
    val state = session.state
    private val safety = CampModeSafety(recoveredSession = saved != null)
    private val telemetry = BydVehicleInfoController(app)
    private val telemetryLock = Mutex()
    private val selectedCutoff = MutableStateFlow(prefs.getInt("batteryCutoff", 10).coerceIn(1, 99))
    val batteryCutoff = selectedCutoff.asStateFlow()
    private val saveSocDevice = BydSaveSocController(app)
    private val saveSocLock = Mutex()
    private val saveSocState = MutableStateFlow(SaveSocStatus())
    val saveSoc = saveSocState.asStateFlow()

    data class SaveSocStatus(
        val reading: BydSaveSocController.Reading? = null,
        val saving: Boolean = false,
    )

    suspend fun refreshSaveSoc() = saveSocLock.withLock {
        if (!saveSocState.value.saving) {
            val reading = io { saveSocDevice.read() }
            if (!saveSocState.value.saving) saveSocState.value = SaveSocStatus(reading)
        }
    }

    fun setSaveSocEnabled(enabled: Boolean) = updateSaveSoc { setEnabled(enabled) }
    fun setSaveSocTarget(target: Int) = updateSaveSoc { setTarget(target) }

    private fun updateSaveSoc(update: suspend BydSaveSocController.() -> Boolean) {
        if (saveSocState.value.saving) return
        saveSocState.value = saveSocState.value.copy(saving = true)
        scope.launch {
            saveSocLock.withLock {
                val (confirmed, reading) = io { saveSocDevice.update() to saveSocDevice.read() }
                saveSocState.value = SaveSocStatus(reading)
                if (!confirmed) Toast.makeText(app, R.string.camp_save_soc_not_confirmed, Toast.LENGTH_LONG).show()
            }
        }
    }

    data class VehicleStatus(
        val gear: BydVehicleInfoController.GearReads? = null,
        val soc: Double? = null,
    )

    val vehicleStatus = flow {
        emit(VehicleStatus())
        while (true) {
            emit(telemetryLock.withLock { io { VehicleStatus(telemetry.readGear(), telemetry.readTractionSoc()) } })
            delay(1000)
        }
    }.distinctUntilChanged()

    private suspend fun readSoc() = telemetryLock.withLock { io { telemetry.readTractionSoc() } }

    fun adjustBatteryCutoff(delta: Int) {
        if (state.value.active || state.value.busy) return
        val updated = (selectedCutoff.value + delta).coerceIn(1, 99)
        prefs.edit().putInt("batteryCutoff", updated).apply()
        selectedCutoff.value = updated
    }

    init {
        OpenDiKeyApp.from(app).dikey.controller.seedLightingSuppression(
            saved?.dikeyLighting != null && prefs.getBoolean("lightingSuppressed", false))
        ContextCompat.registerReceiver(app, CampScreenWakeReceiver { onScreenWake() },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            while (true) {
                state.first { it.awaitingWake && !it.busy }
                session.checkBatteryCutoff(readSoc(), batteryCutoff.value)
                delay(5000)
            }
        }
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

    fun enter() {
        scope.launch {
            if (!state.value.active && !state.value.busy) safety.reset()
            session.enter()
        }
    }
    fun exit() { scope.launch { session.exit() } }
    fun stopWindows() { scope.launch { session.stopWindows() } }

    fun onVehicleSafetySample(
        gear: BydVehicleInfoController.GearReads?,
        power: BydVehicleInfoController.PowerReads,
        resumedAfterSleep: Boolean,
    ) {
        scope.launch {
            val current = state.value
            val reason = safety.sample(
                sessionActive = current.active || current.busy,
                gear = gear,
                ready = power.energyMode?.takeIf { it >= 0 }?.let { it > 0 },
                resumedAfterSleep = resumedAfterSleep,
            ) ?: return@launch
            val message = app.getString(when (reason) {
                CampModeSafety.ExitReason.LEFT_PARK -> R.string.camp_mode_safety_gear_exit
                CampModeSafety.ExitReason.VEHICLE_RESTARTED -> R.string.camp_mode_safety_restart_exit
                CampModeSafety.ExitReason.SESSION_RECOVERED -> R.string.camp_mode_safety_recovered_exit
            })
            Log.i("CampModeController", message)
            session.endForSafety(message)
        }
    }
    fun acknowledgeHeadlightReminder() {
        scope.launch {
            try {
                session.acknowledgeHeadlightReminder()
            } catch (e: IOException) {
                Log.e("CampModeController", "Could not save headlight reminder dismissal.", e)
                Toast.makeText(app, com.sphy.airconcontroller.R.string.camp_mode_reminder_dismiss_failed,
                    Toast.LENGTH_LONG).show()
            } catch (e: SecurityException) {
                Log.e("CampModeController", "Could not save headlight reminder dismissal.", e)
                Toast.makeText(app, com.sphy.airconcontroller.R.string.camp_mode_reminder_dismiss_failed,
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    fun onScreenWake(): Boolean {
        if (!state.value.awaitingWake) return false
        scope.launch { session.onScreenWake() }
        return true
    }

    private inner class VehicleActions : CampActions {
        private val ac = BydAcController(app)
        private val climatePower = CampClimatePower(
            readPower = { io { ac.snapshot().powerOn } },
            stop = {
                val result = io { ac.stop() }
                CampResult(result.success, "${result.method}: ${result.detail}")
            },
        )
        private val windows = BydWindowController(app)
        private val drl = BydDrlController(app)
        private val display = BydDisplayController(app)
        private val cabinDisplays = BydCabinDisplayController(app)
        private val vehicle = BydVehicleInfoController(app)
        private val audio = CampMediaAudio(app)
        private val bluetooth = CampHeadunitBluetooth(app)
        private val carPlay = CampCarPlay(app)
        private val dikey get() = OpenDiKeyApp.from(app).dikey
        private val windowCommands = Mutex()
        private val persistence = Mutex()

        override suspend fun save(): CampSavedState {
            val soc = readSoc()
            val batteryError = when {
                soc == null -> R.string.camp_mode_soc_unavailable
                batteryAtCutoff(soc, batteryCutoff.value) -> R.string.camp_mode_soc_at_cutoff
                else -> null
            }
            if (batteryError != null) {
                Toast.makeText(app, batteryError, Toast.LENGTH_LONG).show()
                error(app.getString(batteryError))
            }
            val lighting = if (dikey.isConnected) dikey.settings.ledRestoreSnapshot() else null
            if (lighting != null) {
                check(lighting.bars.keys.containsAll(listOf(1, 2, 3)) && lighting.backlight != null &&
                    lighting.bars.values.all { it.mode in 0..4 }) {
                    "Cannot read complete DiKey lighting settings; nothing changed."
                }
            }
            return io {
                requirePark()
                val climate = ac.snapshot()
                check(climate.bound && climate.powerOn != null) { "Cannot read the previous climate state; nothing changed." }
                val drlOn = checkNotNull(drl.isOn()) { "Cannot read the previous DRL state; nothing changed." }
                val cluster = cabinDisplays.awaitClusterBrightness()
                val clusterBrightness = checkNotNull(cluster.value) {
                    "Cannot read the previous instrument-cluster brightness; nothing changed. ${cluster.detail}"
                }
                val hud = cabinDisplays.awaitHudState()
                val hudState = checkNotNull(hud.value) {
                    "Cannot read the previous HUD power state; nothing changed. ${hud.detail}"
                }
                val media = audio.snapshot()
                bluetooth.prepare()
                val wakeLog = AdbPermissionManager.runShellCommandQuick(app, wakeLogCommand(System.currentTimeMillis()))
                check(wakeLog.exitCode == 0) {
                    "Cannot monitor the car's backlight wake event: ${wakeLog.output}. Check local ADB; nothing changed."
                }
                CampSavedState(climate, media.volume, media.muted, drlOn, lighting, true,
                    clusterBrightness, hudState, media.usesCarMute)
            }
        }

        override suspend fun dikeyLightsOff(saved: CampSavedState): CampResult {
            val lighting = saved.dikeyLighting ?: return CampResult(true, "DiKey not connected at entry; skipped.")
            val confirmed = dikey.controller.applyCampLighting(enabled = false, lighting)
            return CampResult(confirmed, if (confirmed) "DiKey strip and key-backlight OFF writes acknowledged."
                else "DiKey lighting OFF writes not confirmed.")
        }

        override suspend fun disconnectCarPlay(): CampResult = carPlay.disconnectIfConnected()

        override suspend fun headunitBluetoothOff(): CampResult = bluetooth.setEnabled(false)

        override suspend fun enableHeadunitBluetooth(saved: CampSavedState): CampResult {
            if (!saved.manageHeadunitBluetooth) return CampResult(true, "Older session did not manage head-unit Bluetooth.")
            val result = bluetooth.setEnabled(true)
            if (result.success && saved.dikeyLighting != null) dikey.ensureConnected()
            return result
        }

        override suspend fun enableDikeyLights(saved: CampSavedState): CampResult {
            val lighting = saved.dikeyLighting ?: return CampResult(true, "Session did not change DiKey lighting.")
            repeat(60) {
                if (dikey.isConnected) {
                    val period = dikey.settings.liveLightingPeriod()
                    val profile = dikey.settings.lightingProfile(period)
                    if (profile.bars.values.any { it.mode == LedMode.OFF.code }) {
                        dikey.settings.saveLightingProfile(period, profile.copy(bars = profile.bars.mapValues { (_, bar) ->
                            if (bar.mode == LedMode.OFF.code) bar.copy(mode = LedMode.ON.code) else bar
                        }))
                    }
                    val confirmed = dikey.controller.applyCampLighting(enabled = true, lighting)
                    return CampResult(confirmed, if (confirmed) "DiKey lighting ON writes acknowledged; saved colours preserved."
                        else "DiKey lighting ON writes not confirmed.")
                }
                delay(250)
            }
            return CampResult(false, "DiKey not reconnected; lighting settings retained for restoration retry.")
        }

        private fun requirePark() {
            val gears = vehicle.readGear()
            check(gears?.confirmedPark == true) {
                "Park could not be confirmed ($gears). Select Park before enabling Camp Mode."
            }

        }

        override suspend fun climateOff(): CampResult = climatePower.off()

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

        override suspend fun dimCluster(): CampResult = io {
            val result = cabinDisplays.setClusterBrightness(BydCabinDisplayController.MIN_BRIGHTNESS, ::requirePark)
            CampResult(result.success, result.detail)
        }

        override suspend fun hudOff(): CampResult = io {
            val result = cabinDisplays.setHudState(BydCabinDisplayController.HUD_OFF, ::requirePark)
            CampResult(result.success, result.detail)
        }

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

        override suspend fun restoreClimate(saved: CampSavedState): CampResult = climatePower.off()

        override suspend fun restoreMedia(saved: CampSavedState): CampResult =
            io { audio.restore(saved.mediaVolume, saved.mediaMuted, saved.mediaUsesCarMute) }

        override suspend fun restoreDrl(saved: CampSavedState): CampResult = setDrl(saved.drlOn)

        override suspend fun restoreCluster(saved: CampSavedState): CampResult {
            return io {
                val result = cabinDisplays.restoreClusterBrightness(saved.clusterBrightness, ::requirePark)
                CampResult(result.success, result.detail)
            }
        }

        override suspend fun restoreHud(saved: CampSavedState): CampResult {
            val hudState = saved.hudState
                ?: return CampResult(true, "Older session did not change HUD power.")
            check(hudState in BydCabinDisplayController.HUD_ON..BydCabinDisplayController.HUD_OFF) {
                "Invalid saved HUD power state: $hudState; no command sent."
            }
            return io {
                val result = cabinDisplays.setHudState(hudState, ::requirePark)
                CampResult(result.success, result.detail)
            }
        }

        private suspend fun setDrl(on: Boolean): CampResult {
            val result = io { drl.set(on) }
            repeat(10) {
                delay(150)
                if (io { drl.isOn() } == on) return CampResult(result.success, "DRLs ${if (on) "ON" else "OFF"} confirmed.")
            }

            return CampResult(false, "${result.detail}; DRL read-back=${io { drl.isOn() }}")
        }

        override suspend fun persist(
            saved: CampSavedState?, report: String, completedSteps: Set<CampStep>, remindHeadlights: Boolean,
            failedSteps: Map<CampStep, CampActionError>, sessionError: String?,
        ) {
            persistence.withLock {
                io {
                    val editor = prefs.edit().putString("report", report)
                        .putStringSet("completedSteps", completedSteps.map { it.name }.toSet())
                        .putBoolean("remindHeadlights", remindHeadlights)
                        .putString("failedSteps", encodeErrors(failedSteps).toString())
                        .putString("sessionError", sessionError)
                        .putBoolean("lightingSuppressed", dikey.controller.lightingSuppressed)
                    if (saved == null) editor.remove("saved") else editor.putString("saved", encodeSaved(saved).toString())
                    if (!editor.commit()) throw IOException("Cannot save Camp Mode restoration data.")
                }
            }
        }
    }

    companion object {
        internal fun encodeErrors(errors: Map<CampStep, CampActionError>): JSONObject = JSONObject().apply {
            errors.forEach { (step, error) ->
                put(step.name, JSONObject().put("action", error.action).put("detail", error.detail)
                    .put("duringRestoration", error.duringRestoration))
            }
        }

        internal fun decodeErrors(json: JSONObject): Map<CampStep, CampActionError> =
            json.keys().asSequence().associate { key ->
                val error = json.getJSONObject(key)
                CampStep.valueOf(key) to CampActionError(error.getString("action"), error.getString("detail"),
                    error.optBoolean("duringRestoration", false))
            }

        private fun wakeLogCommand(sinceMillis: Long): String {
            val timestamp = "${sinceMillis / 1000}.${(sinceMillis % 1000).toString().padStart(3, '0')}"
            return "logcat -d -v epoch -b main,system -T '$timestamp' -s TsCarPower:W '*:S'"
        }
        @Volatile private var instance: CampModeController? = null
        fun get(context: Context): CampModeController = instance ?: synchronized(this) {
            instance ?: CampModeController(context).also { instance = it }
        }

        private suspend fun <T> io(block: suspend () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

        internal fun encodeSaved(saved: CampSavedState): JSONObject {
            val ac = saved.climate
            return JSONObject().put("volume", saved.mediaVolume).put("muted", saved.mediaMuted)
                .put("mediaUsesCarMute", saved.mediaUsesCarMute)
                .put("clusterBrightness", saved.clusterBrightness).put("hudState", saved.hudState)
                .put("manageHeadunitBluetooth", saved.manageHeadunitBluetooth)
                .put("dikeyLighting", saved.dikeyLighting?.let {
                    JSONObject(LightingProfile(it.bars, it.backlight).toJson())
                })
                .put("drl", saved.drlOn).put("climate", JSONObject().apply {
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
                if (json.isNull("dikeyLighting")) null else {
                    val lighting = checkNotNull(LightingProfile.fromJson(json.getJSONObject("dikeyLighting").toString())) {
                        "Invalid saved DiKey lighting settings."
                    }
                    LedRestoreSnapshot(lighting.bars, lighting.backlight)
                },
                json.optBoolean("manageHeadunitBluetooth", false),
                if (json.isNull("clusterBrightness")) null else json.getInt("clusterBrightness"),
                if (json.isNull("hudState")) null else json.getInt("hudState"),
                json.optBoolean("mediaUsesCarMute", false),
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
    data class Saved(val volume: Int, val muted: Boolean, val usesCarMute: Boolean = false)
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val carAudio = CampCarMediaAudio { command -> AdbPermissionManager.runShellCommand(app, command) }

    suspend fun snapshot(): Saved {
        if (audio.isVolumeFixed) {
            val state = carAudio.snapshot()
            return Saved(state.media.volume, checkNotNull(state.media.muted), usesCarMute = true)
        }
        return Saved(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    suspend fun mute(): CampResult {
        if (audio.isVolumeFixed) return carAudio.setMuted(true)
        // Only STREAM_MUSIC: do not mute master audio, calls, alarms or safety streams.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return CampResult(level == 0, "Media volume=$level")
    }

    suspend fun restore(volume: Int, muted: Boolean, usesCarMute: Boolean = false): CampResult {
        if (usesCarMute) return carAudio.setMuted(muted, savedVolume = volume)
        check(!audio.isVolumeFixed) { "Cannot restore saved Android media volume on a fixed-volume device." }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        val after = snapshot()
        return CampResult(after.volume == volume && after.muted == muted, "Media volume=${after.volume} muted=${after.muted}")
    }
}
