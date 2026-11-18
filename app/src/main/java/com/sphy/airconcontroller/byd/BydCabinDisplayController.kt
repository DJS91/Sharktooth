package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.lang.reflect.InvocationTargetException

/** Cluster brightness and HUD power, independently verified on BYD AUTO / Android 11. */
internal class BydCabinDisplayController internal constructor(
    private val instrument: () -> Any?,
    private val setting: () -> Any?,
    private val log: (String, Throwable?) -> Unit = { msg, error -> Log.w(TAG, msg, error) },
) {
    constructor(context: Context) : this(
        deviceLoader(context.applicationContext, INSTRUMENT_CLASS),
        deviceLoader(context.applicationContext, SETTING_CLASS),
    )

    data class Reading(val value: Int?, val detail: String)
    data class Result(val success: Boolean, val detail: String)

    fun readClusterBrightness(): Reading =
        read(instrument, "getBacklightBrightness", MIN_BRIGHTNESS..MAX_BRIGHTNESS)

    fun readHudState(): Reading = read(setting, "getHudState", HUD_ON..HUD_OFF)

    suspend fun awaitClusterBrightness(): Reading = await(::readClusterBrightness)
    suspend fun awaitHudState(): Reading = await(::readHudState)

    suspend fun setClusterBrightness(value: Int, beforeWrite: () -> Unit): Result =
        set(instrument, "getBacklightBrightness", "setBacklightBrightness",
            value, MIN_BRIGHTNESS..MAX_BRIGHTNESS, beforeWrite)

    suspend fun setHudState(value: Int, beforeWrite: () -> Unit): Result =
        set(setting, "getHudState", "setHudState", value, HUD_ON..HUD_OFF, beforeWrite)

    private suspend fun await(reading: () -> Reading): Reading {
        var result = reading()
        repeat(10) {
            if (result.value != null) return result
            delay(150)
            result = reading()
        }
        return result
    }

    private fun read(device: () -> Any?, getter: String, range: IntRange): Reading {
        val problems = mutableListOf<String>()
        val value = attempt(getter, problems) {
            val dev = checkNotNull(device()) { "SDK device unavailable" }
            val raw = dev.javaClass.getMethod(getter).invoke(dev)
            val number = (raw as? Number)?.toInt()
            check(number != null && number in range) { "Invalid reading: $raw (expected $range)" }
            number
        }
        return Reading(value, if (value != null) "$getter=$value" else problems.joinToString("; "))
    }

    private suspend fun set(
        device: () -> Any?,
        getter: String,
        setter: String,
        value: Int,
        range: IntRange,
        beforeWrite: () -> Unit,
    ): Result {
        require(value in range) { "Invalid $setter value: $value (expected $range)" }
        val before = read(device, getter, range)
        if (before.value == null) return Result(false, "No command sent: ${before.detail}")
        if (before.value == value) return Result(true, "$getter already $value; no command sent.")
        val label = "$setter($value)"
        val problems = mutableListOf<String>()
        val accepted = attempt(label, problems) {
            val dev = checkNotNull(device()) { "SDK device unavailable" }
            val method = dev.javaClass.getMethod(setter, Int::class.javaPrimitiveType)
            beforeWrite()
            val raw = method.invoke(dev, value)
            check((raw as? Number)?.toInt() == 0) { "Command rejected: result=$raw" }
            true
        } == true
        if (!accepted) return Result(false, problems.joinToString("; "))
        var after = before
        repeat(10) {
            delay(150)
            after = read(device, getter, range)
            if (after.value == value) return Result(true, "$label confirmed; ${after.detail}")
        }
        return Result(false, "$label accepted but not confirmed; ${after.detail}").also { log(it.detail, null) }
    }

    private fun <T> attempt(label: String, problems: MutableList<String>, block: () -> T): T? {
        fun failed(error: Throwable) {
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            problems += "$label: ${cause.javaClass.simpleName}: ${cause.message}"
            log(problems.last(), cause)
        }
        return try {
            block()
        } catch (e: ReflectiveOperationException) {
            failed(e)
            null
        } catch (e: SecurityException) {
            failed(e)
            null
        } catch (e: IllegalStateException) {
            if (e is CancellationException) throw e
            failed(e)
            null
        } catch (e: LinkageError) {
            failed(e)
            null
        }
    }

    companion object {
        const val MIN_BRIGHTNESS = 1
        const val MAX_BRIGHTNESS = 22
        const val HUD_ON = 1
        const val HUD_OFF = 2
        private const val TAG = "BydCabinDisplay"
        private const val INSTRUMENT_CLASS = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
        private const val SETTING_CLASS = "android.hardware.bydauto.setting.BYDAutoSettingDevice"

        private fun deviceLoader(app: Context, className: String): () -> Any? {
            var cached: Any? = null
            return {
                cached ?: run {
                    Dilink5SdkInjector.ensure(app)
                    checkNotNull(Class.forName(className, true, app.classLoader)
                        .getMethod("getInstance", Context::class.java).invoke(null, app)) {
                        "$className.getInstance returned null"
                    }
                }.also { cached = it }
            }
        }
    }
}
