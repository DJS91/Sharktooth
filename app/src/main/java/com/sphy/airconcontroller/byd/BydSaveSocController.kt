package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay

internal class BydSaveSocController(
    private val charging: () -> Any?,
    private val setting: () -> Any?,
    private val pause: suspend () -> Unit = { delay(150) },
    private val log: (String, Throwable) -> Unit = { method, cause -> Log.w("BydSaveSoc", method, cause) },
) {
    constructor(context: Context) : this(
        loader(context.applicationContext, "android.hardware.bydauto.charging.BYDAutoChargingDevice"),
        loader(context.applicationContext, "android.hardware.bydauto.setting.BYDAutoSettingDevice"),
    )

    data class Reading(val enabled: Boolean?, val target: Int?, val maximum: Int)

    fun read(): Reading {
        val device = setting()
        val maximum = device?.let { sdkCall("SET_DR_SOC_TARGET_MAX", reportMissing = false) {
            it.javaClass.getField("SET_DR_SOC_TARGET_MAX").getInt(null)
        } }?.takeIf { it in MIN_TARGET + 1..MAX_TARGET } ?: MAX_TARGET
        val enabled = when (invoke(charging(), "getSocSaveSwitch")) {
            1 -> false
            2 -> true
            else -> null
        }
        val target = invoke(device, "getSOCTarget")?.takeIf { it in 1..MAX_TARGET }
        return Reading(enabled, target, maximum)
    }

    suspend fun setEnabled(enabled: Boolean): Boolean {
        val before = read().enabled ?: return false
        if (before == enabled) return true
        if (invoke(charging(), "setSocSaveSwitch", if (enabled) 2 else 1) == null) return false
        return confirm { read().enabled == enabled }
    }

    suspend fun setTarget(target: Int): Boolean {
        val before = read()
        if (before.enabled != true || before.target == null || target !in MIN_TARGET..before.maximum) return false
        if (before.target == target) return true
        if (invoke(setting(), "setSOCTarget", target) == null) return false
        return confirm { read().let { it.enabled == true && it.target == target } }
    }

    private suspend fun confirm(matches: () -> Boolean): Boolean {
        repeat(10) {
            if (matches()) return true
            pause()
        }
        return matches()
    }

    private fun invoke(device: Any?, method: String, value: Int? = null): Int? =
        device?.let {
            sdkCall(method) {
                if (value == null) it.javaClass.getMethod(method).invoke(it) as? Int
                else it.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(it, value) as? Int
            }
        }

    private fun <T> sdkCall(method: String, reportMissing: Boolean = true, block: () -> T): T? =
        try {
            block()
        } catch (e: ReflectiveOperationException) {
            if (reportMissing || e !is NoSuchFieldException) log(method, e.cause ?: e)
            null
        } catch (e: SecurityException) {
            log(method, e)
            null
        } catch (e: LinkageError) {
            log(method, e)
            null
        }

    companion object {
        const val MIN_TARGET = 25
        const val MAX_TARGET = 100

        private fun loader(app: Context, className: String): () -> Any? {
            var cached: Any? = null
            return {
                cached ?: try {
                    if (!Dilink5SdkInjector.ensure(app)) null
                    else Class.forName(className, true, app.classLoader)
                        .getMethod("getInstance", Context::class.java).invoke(null, app)
                        .also { cached = it }
                } catch (e: ReflectiveOperationException) {
                    Log.w("BydSaveSoc", "Cannot bind $className", e.cause ?: e)
                    null
                } catch (e: SecurityException) {
                    Log.w("BydSaveSoc", "Cannot bind $className", e)
                    null
                } catch (e: LinkageError) {
                    Log.w("BydSaveSoc", "Cannot bind $className", e)
                    null
                }
            }
        }
    }
}
