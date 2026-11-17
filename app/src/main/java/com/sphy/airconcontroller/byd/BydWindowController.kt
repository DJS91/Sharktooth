package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * All-side-window commands via `BYDAutoBodyworkDevice`, using the mapping proven by Overdrive:
 * slots are [front-left, front-right, rear-left, rear-right]; 1 = open, 2 = close, 3 = stop.
 * DiLink 5 stubs `setAllWindowState`, so its `setWindowControlState(int[])` is the fallback.
 */
class BydWindowController internal constructor(
    private val device: () -> Any?,
    private val log: (String, Throwable?) -> Unit = { msg, t -> if (t == null) Log.i(TAG, msg) else Log.w(TAG, msg, t) },
) {
    constructor(context: Context) : this(deviceLoader(context.applicationContext))

    data class CommandResult(val success: Boolean, val method: String, val detail: String)

    fun closeAll(): CommandResult = sendAll(CLOSE)

    fun stopAll(): CommandResult = sendAll(STOP)

    /** Open percentage (0 = closed) per window, or null when the car gives no valid reading. */
    fun positions(): Map<String, Int?> {
        val dev = device()
        return AREAS.mapValues { (_, area) ->
            runCatching {
                dev?.javaClass?.getMethod("getWindowOpenPercent", Int::class.javaPrimitiveType)
                    ?.invoke(dev, area) as? Number
            }.getOrNull()?.toInt()?.takeIf { it in 0..100 }
        }
    }

    private fun sendAll(command: Int): CommandResult {
        val dev = device() ?: return CommandResult(false, "bodywork", "BYDAutoBodyworkDevice unavailable")
        val legacy = invoke(dev, "setAllWindowState", Array(4) { Int::class.javaPrimitiveType!! }, Array(4) { command })
        if (legacy.success || legacy.reached) return legacy.result
        val dilink5 = invoke(dev, "setWindowControlState", arrayOf(IntArray::class.java), arrayOf(IntArray(4) { command }))
        if (dilink5.reached) return dilink5.result
        return CommandResult(false, "bodywork", "${legacy.result.detail}; ${dilink5.result.detail}")
    }

    private class Attempt(val result: CommandResult, val reached: Boolean) {
        val success get() = result.success
    }

    /** [Attempt.reached] is false only when the method is missing or a stub, so the next API can be tried. */
    private fun invoke(dev: Any, name: String, types: Array<Class<*>>, args: Array<Any>): Attempt {
        val label = "$name(${args.joinToString { if (it is IntArray) it.contentToString() else it.toString() }})"
        return try {
            val raw = dev.javaClass.getMethod(name, *types).invoke(dev, *args)
            // Bodywork setters return 0 when the car accepts the command.
            val accepted = (raw as? Number)?.toInt() == 0
            log("$label -> $raw accepted=$accepted", null)
            Attempt(CommandResult(accepted, label, "result=$raw"), reached = true)
        } catch (e: NoSuchMethodException) {
            Attempt(CommandResult(false, label, "$name unavailable"), reached = false)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            log("$label failed", cause)
            Attempt(CommandResult(false, label, "${cause.javaClass.simpleName}: ${cause.message}"), reached = false)
        } catch (e: ReflectiveOperationException) {
            Attempt(CommandResult(false, label, "${e.javaClass.simpleName}: ${e.message}"), reached = false)
        }
    }

    companion object {
        private const val TAG = "BydWindowController"
        private const val CLOSE = 2
        private const val STOP = 3
        private const val BODYWORK_CLASS = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
        val AREAS = linkedMapOf("Front left" to 1, "Front right" to 2, "Rear left" to 3, "Rear right" to 4)

        private fun deviceLoader(app: Context): () -> Any? {
            var cached: Any? = null
            return {
                cached ?: run {
                    Dilink5SdkInjector.ensure(app)
                    runCatching {
                        val getInstance = Class.forName(BODYWORK_CLASS, true, app.classLoader)
                            .getMethod("getInstance", Context::class.java)
                        runCatching { getInstance.invoke(null, BydPermissionContext(app)) }.getOrNull()
                            ?: getInstance.invoke(null, app)
                    }.onFailure { Log.w(TAG, "Bodywork unavailable", it) }.getOrNull()
                }.also { cached = it }
            }
        }
    }
}
