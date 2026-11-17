package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException

/** OEM headlight selector on the instrument device, separate from DRLs and beam-height adjustment. */
class BydHeadlightController internal constructor(
    private val device: () -> Any?,
    private val log: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
) {
    constructor(context: Context) : this(deviceLoader(context.applicationContext))

    data class CommandResult(val success: Boolean, val detail: String)

    /** 1=OFF, 2=AUTO, 3=parking lights, 4=low beam; all other readings are unavailable. */
    fun mode(): Int? {
        val dev = device() ?: return null
        return try {
            val raw = dev.javaClass.getMethod("getHeadlightControlMode").invoke(dev) as? Number
            raw?.toInt()?.takeIf { it in OFF..LOW_BEAM }.also {
                if (it == null) log("Invalid headlight selector reading: $raw", null)
            }
        } catch (e: ReflectiveOperationException) {
            log("Cannot read headlight selector", (e as? InvocationTargetException)?.targetException ?: e)
            null
        }
    }

    /** The caller must enforce Park for OFF and confirm the selector using [mode] afterwards. */
    fun setMode(mode: Int): CommandResult {
        require(mode in OFF..LOW_BEAM) { "Invalid headlight selector mode: $mode" }
        val dev = device() ?: return CommandResult(false, "BYDAutoInstrumentDevice unavailable")
        return try {
            val raw = dev.javaClass.getMethod("setHeadlightControlMode", Int::class.javaPrimitiveType)
                .invoke(dev, mode) as? Number
            CommandResult(raw?.toInt() == 0, "setHeadlightControlMode($mode) result=$raw")
        } catch (e: ReflectiveOperationException) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            log("Cannot set headlight selector", cause)
            CommandResult(false, "${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    companion object {
        const val OFF = 1
        const val AUTO = 2
        const val PARKING = 3
        const val LOW_BEAM = 4
        private const val TAG = "BydHeadlightController"
        private const val INSTRUMENT_CLASS = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"

        private fun deviceLoader(app: Context): () -> Any? {
            var cached: Any? = null
            return {
                cached ?: run {
                    Dilink5SdkInjector.ensure(app)
                    runCatching {
                        val getInstance = Class.forName(INSTRUMENT_CLASS, true, app.classLoader)
                            .getMethod("getInstance", Context::class.java)
                        runCatching { getInstance.invoke(null, BydPermissionContext(app)) }.getOrNull()
                            ?: getInstance.invoke(null, app)
                    }.onFailure { Log.w(TAG, "Instrument device unavailable", it) }.getOrNull()
                }.also { cached = it }
            }
        }
    }
}
