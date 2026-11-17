package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Daytime running lights via `BYDAutoLightDevice`, matching Overdrive's mapping:
 * `setDayTimeLightState` / `getDayTimeLightState` use 1 = on, 2 = off.
 * The car may return 0 without switching (e.g. outside Park), so callers must confirm with [isOn].
 */
class BydDrlController internal constructor(
    private val device: () -> Any?,
    private val log: (String, Throwable?) -> Unit = { msg, t -> if (t == null) Log.i(TAG, msg) else Log.w(TAG, msg, t) },
) {
    constructor(context: Context) : this(deviceLoader(context.applicationContext))

    data class CommandResult(val success: Boolean, val method: String, val detail: String)

    fun set(on: Boolean): CommandResult {
        val value = if (on) ON else OFF
        val label = "setDayTimeLightState($value)"
        val dev = device() ?: return CommandResult(false, label, "BYDAutoLightDevice unavailable")
        return try {
            val raw = dev.javaClass.getMethod("setDayTimeLightState", Int::class.javaPrimitiveType).invoke(dev, value)
            val accepted = (raw as? Number)?.toInt() == 0
            log("$label -> $raw accepted=$accepted", null)
            CommandResult(accepted, label, "result=$raw")
        } catch (e: InvocationTargetException) {
            log("$label failed", e.targetException)
            CommandResult(false, label, "${e.targetException.javaClass.simpleName}: ${e.targetException.message}")
        } catch (e: ReflectiveOperationException) {
            CommandResult(false, label, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** True/false when the car reports a valid state, null otherwise. */
    fun isOn(): Boolean? {
        val dev = device() ?: return null
        val raw = runCatching { dev.javaClass.getMethod("getDayTimeLightState").invoke(dev) as? Number }
            .getOrNull()?.toInt()
        return when (raw) {
            ON -> true
            OFF -> false
            else -> null
        }
    }

    companion object {
        private const val TAG = "BydDrlController"
        private const val ON = 1
        private const val OFF = 2
        private const val LIGHT_CLASS = "android.hardware.bydauto.light.BYDAutoLightDevice"

        private fun deviceLoader(app: Context): () -> Any? {
            var cached: Any? = null
            return {
                cached ?: run {
                    Dilink5SdkInjector.ensure(app)
                    runCatching {
                        val getInstance = Class.forName(LIGHT_CLASS, true, app.classLoader)
                            .getMethod("getInstance", Context::class.java)
                        runCatching { getInstance.invoke(null, BydPermissionContext(app)) }.getOrNull()
                            ?: getInstance.invoke(null, app)
                    }.onFailure { Log.w(TAG, "Light device unavailable", it) }.getOrNull()
                }.also { cached = it }
            }
        }
    }
}
