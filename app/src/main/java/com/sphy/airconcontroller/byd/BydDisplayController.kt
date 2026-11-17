package com.sphy.airconcontroller.byd

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.lang.reflect.InvocationTargetException
import kotlin.math.roundToInt

/**
 * Centre-display brightness and backlight, following Overdrive's approach:
 * - brightness: `BYDAutoSettingDevice.setInfotainmentBrightness(0..100)` plus Android's system
 *   brightness (manual mode), since the SDK setter is a no-op on some trims;
 * - backlight: `BYDAutoSettingDevice.turnBacklightOff/On()`, then hidden PowerManager
 *   `turnBacklightOff/On(long)` / `TurnBacklightOff/On(long)`.
 */
class BydDisplayController(context: Context) {
    private val app = context.applicationContext
    @Volatile private var settingDevice: Any? = null
    @Volatile private var loaded = false

    data class Saved(val brightness: Int?, val mode: Int?)
    data class Result(val success: Boolean, val detail: String)

    fun canWriteSystem(): Boolean = Settings.System.canWrite(app)

    /** Loads the BYD SDK off the main thread so later commands are quick. */
    fun prepare() {
        if (loaded) return
        Dilink5SdkInjector.ensure(app)
        settingDevice = runCatching {
            val getInstance = Class.forName(SETTING_CLASS, true, app.classLoader)
                .getMethod("getInstance", Context::class.java)
            runCatching { getInstance.invoke(null, BydPermissionContext(app)) }.getOrNull()
                ?: getInstance.invoke(null, app)
        }.onFailure { Log.w(TAG, "Setting device unavailable", it) }.getOrNull()
        loaded = true
    }

    fun save(): Saved {
        val resolver = app.contentResolver
        return Saved(
            runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull(),
            runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrNull(),
        )
    }

    fun setBrightness(percent: Int): Result {
        val sdk = callSetting("setInfotainmentBrightness", percent)
        val system = writeSystem((percent * 255 / 100f).roundToInt(), Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        return Result(sdk.success || system.success, "${sdk.detail}; ${system.detail}")
    }

    fun restore(saved: Saved): Result {
        val level = saved.brightness ?: return Result(false, "previous brightness unknown; nothing restored")
        val sdk = callSetting("setInfotainmentBrightness", (level * 100 / 255f).roundToInt())
        val system = writeSystem(level, saved.mode ?: Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        return Result(sdk.success || system.success, "${sdk.detail}; ${system.detail}")
    }

    /** Tries each backlight route until one is accepted; the report lists every attempt. */
    fun backlight(on: Boolean): Result {
        val suffix = if (on) "On" else "Off"
        val attempts = mutableListOf<String>()
        val sdk = callSetting("turnBacklight$suffix")
        attempts += sdk.detail
        if (sdk.success) return Result(true, attempts.joinToString("; "))
        val power = app.getSystemService(PowerManager::class.java)
        for (name in listOf("turnBacklight$suffix", "TurnBacklight$suffix")) {
            val label = "PowerManager.$name(long)"
            try {
                power.javaClass.getMethod(name, Long::class.javaPrimitiveType).invoke(power, SystemClock.uptimeMillis())
                attempts += "$label accepted"
                return Result(true, attempts.joinToString("; "))
            } catch (e: NoSuchMethodException) {
                attempts += "$label unavailable"
            } catch (e: InvocationTargetException) {
                attempts += "$label ${e.targetException.javaClass.simpleName}: ${e.targetException.message}"
            } catch (e: ReflectiveOperationException) {
                attempts += "$label ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return Result(false, attempts.joinToString("; "))
    }

    private fun writeSystem(brightness: Int, mode: Int): Result {
        if (!canWriteSystem()) return Result(false, "system brightness: 'Modify system settings' not allowed")
        return try {
            val resolver = app.contentResolver
            val level = brightness.coerceIn(1, 255)
            // Manual first, so auto-brightness can't immediately override the level.
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
            if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            }
            Result(true, "system brightness=$level/255 mode=$mode")
        } catch (e: SecurityException) {
            Result(false, "system brightness: ${e.message}")
        }
    }

    /** BYD display setters return no reliable code, so (like Overdrive) no exception means accepted. */
    private fun callSetting(name: String, vararg args: Int): Result {
        val label = "Setting.$name(${args.joinToString()})"
        if (!loaded) prepare()
        val dev = settingDevice ?: return Result(false, "$label: BYDAutoSettingDevice unavailable")
        return try {
            val types = Array(args.size) { Int::class.javaPrimitiveType!! }
            val raw = dev.javaClass.getMethod(name, *types).invoke(dev, *args.toTypedArray())
            Log.i(TAG, "$label -> $raw")
            Result(true, "$label result=$raw")
        } catch (e: NoSuchMethodException) {
            Result(false, "$label unavailable")
        } catch (e: InvocationTargetException) {
            Result(false, "$label ${e.targetException.javaClass.simpleName}: ${e.targetException.message}")
        } catch (e: ReflectiveOperationException) {
            Result(false, "$label ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "BydDisplayController"
        const val SETTING_CLASS = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
    }
}
