package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay

/** Setting-device energy feedback API documented in BYD API V1.0.5, page 138. */
internal class BydRegenController(
    private val device: () -> Any?,
    private val pause: suspend () -> Unit = { delay(150) },
    private val log: (String, Throwable?) -> Unit = { text, error -> Log.w("BydRegen", text, error) },
) {
    enum class Mode { STANDARD, LARGER }
    data class Reading(val mode: Mode?, val detail: String)
    data class Result(val success: Boolean, val detail: String)

    constructor(context: Context) : this(loader(context.applicationContext))

    private fun constants(dev: Any): Map<Mode, Int> = mapOf(
        Mode.STANDARD to dev.javaClass.getField("SET_DR_ENERGY_FB_STANDARD").getInt(null),
        Mode.LARGER to dev.javaClass.getField("SET_DR_ENERGY_FB_LARGE").getInt(null),
    ).also { check(it.values.distinct().size == 2) { "Invalid energy feedback constants" } }

    fun read(): Reading = attempt {
        val dev = checkNotNull(device()) { "BYD Setting device unavailable" }
        val modes = constants(dev)
        val raw = dev.javaClass.getMethod("getEnergyFeedback").invoke(dev) as? Int
        val mode = checkNotNull(modes.entries.find { it.value == raw }?.key) {
            "Energy feedback reading unavailable: $raw"
        }
        Reading(mode, "")
    }.let { (reading, error) -> reading ?: Reading(null, checkNotNull(error)) }

    suspend fun setMode(mode: Mode): Result {
        val before = read()
        if (before.mode == null) return Result(false, before.detail)
        if (before.mode == mode) return Result(true, "")
        val (_, error) = attempt {
            val dev = checkNotNull(device()) { "BYD Setting device unavailable" }
            val value = constants(dev).getValue(mode)
            val result = dev.javaClass.getMethod("setEnergyFeedback", Int::class.javaPrimitiveType)
                .invoke(dev, value) as? Int
            check(result != null && result >= 0) { "setEnergyFeedback rejected command: $result" }
        }
        if (error != null) return Result(false, error)
        repeat(10) {
            if (read().mode == mode) return Result(true, "")
            pause()
        }
        if (read().mode == mode) return Result(true, "")
        val detail = "Energy feedback change was not confirmed by the vehicle"
        log(detail, null)
        return Result(false, detail)
    }

    private fun <T> attempt(block: () -> T): Pair<T?, String?> {
        fun failed(error: Throwable): Pair<T?, String?> {
            val cause = error.cause ?: error
            val detail = "${cause.javaClass.simpleName}: ${cause.message}"
            log(detail, cause)
            return null to detail
        }
        return try { block() to null }
        catch (e: ReflectiveOperationException) { failed(e) }
        catch (e: SecurityException) { failed(e) }
        catch (e: IllegalStateException) { failed(e) }
        catch (e: LinkageError) { failed(e) }
    }

    companion object {
        private fun loader(app: Context): () -> Any? {
            var cached: Any? = null
            return {
                cached ?: run {
                    check(Dilink5SdkInjector.ensure(app)) { "BYD SDK unavailable" }
                    Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice", true, app.classLoader)
                        .getMethod("getInstance", Context::class.java).invoke(null, app)
                        .also { cached = it }
                }
            }
        }
    }
}
