package com.sphy.airconcontroller.byd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import java.lang.reflect.InvocationTargetException
import kotlin.math.roundToInt

/**
 * Centre-display brightness and screen off:
 * - brightness (Overdrive's approach): `BYDAutoSettingDevice.setInfotainmentBrightness(0..100)` plus
 *   Android's system brightness (manual mode), since the SDK setter is a no-op on some trims;
 * - screen off: the car's own "Screen off" shortcut, which starts BYD Car Settings' DialogService
 *   with a `screen_saver` link; the car turns the backlight off and wakes it on touch.
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

    /** Starts the car's screen saver directly; it's a non-exported service on some builds, so callers fall back to ADB. */
    fun startCarScreenSaver(): Result = try {
        val started = app.startService(Intent(Intent.ACTION_VIEW, Uri.parse(SCREEN_SAVER_URI)).setComponent(SCREEN_SAVER_SERVICE))
        if (started != null) Result(true, "startService(DialogService) accepted")
        else Result(false, "startService(DialogService): BYD Car Settings not installed")
    } catch (e: SecurityException) {
        Result(false, "startService(DialogService) SecurityException: ${e.message}")
    } catch (e: IllegalStateException) {
        Result(false, "startService(DialogService) IllegalStateException: ${e.message}")
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

    companion object {
        private const val TAG = "BydDisplayController"
        private const val SETTING_CLASS = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
        // Captured from the car's own "Screen off" shortcut.
        private const val SCREEN_SAVER_URI = "byd_pad://byd_car_settings:2935/launcher?action=screen_saver"
        private val SCREEN_SAVER_SERVICE = ComponentName("com.byd.carsettings", "com.byd.dialogservice.DialogService")
        const val SCREEN_SAVER_SHELL =
            "am startservice -a android.intent.action.VIEW -d '$SCREEN_SAVER_URI' -n com.byd.carsettings/com.byd.dialogservice.DialogService"
    }
}
