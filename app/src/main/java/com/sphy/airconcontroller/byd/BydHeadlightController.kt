package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** OEM headlight selector on the instrument device, separate from DRLs and beam-height adjustment. */
class BydHeadlightController internal constructor(
    private val device: () -> Any?,
    private val log: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
) {
    constructor(context: Context) : this(deviceLoader(context.applicationContext))

    data class CommandResult(val success: Boolean, val detail: String)
    data class Reading(val mode: Int?, val detail: String)

    /** 1=OFF, 2=AUTO, 3=parking lights, 4=low beam; all other readings are unavailable. */
    fun mode(): Int? = readMode().mode

    fun readMode(): Reading {
        val problems = mutableListOf<String>()
        val dev = attempt("Instrument binding", problems) { device() }
            ?: return Reading(null, problems.joinToString("; ").ifEmpty { "BYDAutoInstrumentDevice unavailable" })
        val named = attempt("getHeadlightControlMode", problems) {
            validMode(findMethod(dev.javaClass, "getHeadlightControlMode").invoke(dev), problems, "getHeadlightControlMode")
        }
        if (named != null) return Reading(named, "getHeadlightControlMode=$named")
        val feedback = readFeedback(dev, problems)
        return feedback.copy(detail = (problems + feedback.detail).joinToString("; "))
    }

    suspend fun awaitMode(): Reading {
        var reading = readMode()
        repeat(10) {
            if (reading.mode != null) return reading
            delay(150)
            reading = readMode()
        }
        return if (reading.mode != null) reading else reading.copy(
            detail = "Still unavailable after waiting 1.5s for SDK feedback: ${reading.detail}")
    }

    private fun readFeedback(dev: Any, problems: MutableList<String>): Reading {
        val unavailable = Reading(null, "No valid headlight selector feedback")
        val id = attempt("Resolve instrument headlight feedback ID", problems) {
            featureId(dev, "INSTRUMENT_HEADLIGHT_CONTROL_FEEDBACK", FEEDBACK_FEATURE_ID)
        } ?: return unavailable
        val getter = attempt("Instrument.get(int[], Class)", problems) {
            findMethod(dev.javaClass, "get", IntArray::class.java, Class::class.java)
        } ?: return unavailable
        fun read(type: Class<*>): Reading? {
            val label = "Instrument.get($id, ${type.simpleName})"
            val mode = attempt(label, problems) {
                validMode(getter.invoke(dev, intArrayOf(id), type), problems, label)
            } ?: return null
            return Reading(mode, "Instrument headlight feedback=$mode via $label")
        }
        read(Int::class.java)?.let { return it }
        val eventType = attempt("Load $EVENT_CLASS for feedback", problems) {
            Class.forName(EVENT_CLASS, true, dev.javaClass.classLoader)
        } ?: return unavailable
        return read(eventType) ?: unavailable
    }

    private fun validMode(raw: Any?, problems: MutableList<String>, label: String): Int? {
        val value = when (raw) {
            null -> null
            is Number -> raw.toInt()
            else -> raw.javaClass.getField("intValue").getInt(raw)
        }
        return value?.takeIf { it in OFF..LOW_BEAM }.also {
            if (it == null) {
                val detail = "$label: Invalid headlight selector reading: $value"
                problems += detail
                log(detail, null)
            }
        }
    }

    /** The caller must enforce Park for OFF and confirm the selector using [mode] afterwards. */
    fun setMode(mode: Int): CommandResult {
        require(mode in OFF..LOW_BEAM) { "Invalid headlight selector mode: $mode" }
        val problems = mutableListOf<String>()
        val dev = attempt("Instrument binding", problems) { device() }
            ?: return CommandResult(false, problems.joinToString("; ").ifEmpty { "BYDAutoInstrumentDevice unavailable" })
        return attempt("Set headlight selector", problems) {
            val named = try {
                findMethod(dev.javaClass, "setHeadlightControlMode", Int::class.java)
            } catch (_: NoSuchMethodException) {
                null
            }
            if (named != null) {
                val raw = named.invoke(dev, mode) as? Number
                CommandResult(raw?.toInt() == 0, "setHeadlightControlMode($mode) result=$raw")
            } else {
                // Only use the legacy selector command with readable, validated selector feedback.
                if (readFeedback(dev, problems).mode == null) {
                    CommandResult(false, "Legacy headlight selector feedback unavailable; no command sent. ${problems.joinToString("; ")}")
                } else {
                    val id = featureId(dev, "INSTRUMENT_HEADLIGHT_CONTROL_SET", SET_FEATURE_ID)
                    val eventType = Class.forName(EVENT_CLASS, true, dev.javaClass.classLoader)
                    val event = eventType.getDeclaredConstructor().newInstance()
                    eventType.getField("intValue").setInt(event, mode)
                    val raw = findMethod(dev.javaClass, "set", IntArray::class.java, eventType)
                        .invoke(dev, intArrayOf(id), event)
                    val accepted = when (raw) {
                        is Number -> raw.toInt() == 0
                        is Boolean -> raw
                        else -> false
                    }
                    CommandResult(accepted, "Instrument.set($id, $mode) result=$raw; read-back required")
                }
            }
        } ?: CommandResult(false, problems.joinToString("; "))
    }

    private fun featureId(dev: Any, name: String, fallback: Int): Int {
        val id = try {
            Class.forName(INSTRUMENT_FEATURES_CLASS, true, dev.javaClass.classLoader)
                .getField(name).getInt(null)
        } catch (_: ClassNotFoundException) {
            log("$INSTRUMENT_FEATURES_CLASS unavailable; using compatibility $name=$fallback", null)
            fallback
        } catch (_: NoSuchFieldException) {
            log("SDK does not export $name; using compatibility ID=$fallback", null)
            fallback
        }
        check(id > 0) { "SDK exports invalid $name=$id; no feature command sent" }
        return id
    }

    private fun findMethod(type: Class<*>, name: String, vararg parameters: Class<*>): Method {
        try {
            return type.getMethod(name, *parameters)
        } catch (_: NoSuchMethodException) {
            var parent: Class<*>? = type
            while (parent != null) {
                try {
                    return parent.getDeclaredMethod(name, *parameters).apply { isAccessible = true }
                } catch (_: NoSuchMethodException) {
                    parent = parent.superclass
                }
            }
            throw NoSuchMethodException("${type.name}.$name(${parameters.joinToString { it.simpleName }})")
        }
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
            failed(e)
            null
        } catch (e: IllegalArgumentException) {
            failed(e)
            null
        } catch (e: LinkageError) {
            failed(e)
            null
        }
    }

    companion object {
        const val OFF = 1
        const val AUTO = 2
        const val PARKING = 3
        const val LOW_BEAM = 4
        private const val TAG = "BydHeadlightController"
        private const val INSTRUMENT_CLASS = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
        private const val EVENT_CLASS = "android.hardware.bydauto.BYDAutoEventValue"
        private const val INSTRUMENT_FEATURES_CLASS = "android.hardware.bydauto.BYDAutoFeatureIds\$Instrument"
        private const val FEEDBACK_FEATURE_ID = 1011875880
        private const val SET_FEATURE_ID = 1276153912

        private fun deviceLoader(app: Context): () -> Any? {
            var cached: Any? = null
            return {
                cached ?: run {
                    Dilink5SdkInjector.ensure(app)
                    val getInstance = Class.forName(INSTRUMENT_CLASS, true, app.classLoader)
                        .getMethod("getInstance", Context::class.java)
                    val wrapped = try {
                        getInstance.invoke(null, BydPermissionContext(app))
                    } catch (e: ReflectiveOperationException) {
                        Log.w(TAG, "Instrument binding with permission context failed", e)
                        null
                    }
                    wrapped ?: checkNotNull(getInstance.invoke(null, app)) {
                        "BYDAutoInstrumentDevice.getInstance returned null"
                    }
                }.also { cached = it }
            }
        }
    }
}
