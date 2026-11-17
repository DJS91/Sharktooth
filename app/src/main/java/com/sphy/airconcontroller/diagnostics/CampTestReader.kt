package com.sphy.airconcontroller.diagnostics

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.byd.BydHeadlightController
import com.sphy.airconcontroller.byd.BydPermissionContext
import com.sphy.airconcontroller.byd.Dilink5SdkInjector
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/** Explicit reads only; none of the discovered vehicle setters are invoked. */
class CampTestReader(context: Context) {
    private val app = context.applicationContext
    val climate = BydAcController(app)
    private val headlights = BydHeadlightController(app)

    fun capture(): CampTestSnapshot {
        val values = linkedMapOf<String, String>()
        val errors = linkedMapOf<String, String>()
        fun read(key: String, block: () -> Any?) {
            try {
                val value = block()
                if (value == null) errors[key] = "No value returned" else values[key] = value.toString()
            } catch (e: ReflectiveOperationException) {
                val cause = (e as? InvocationTargetException)?.targetException ?: e
                errors[key] = "${cause.javaClass.simpleName}: ${cause.message}"
                Log.w(TAG, "Cannot read $key", cause)
            } catch (e: SecurityException) {
                errors[key] = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "Cannot read $key", e)
            } catch (e: LinkageError) {
                errors[key] = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "Cannot load $key", e)
            } catch (e: IllegalArgumentException) {
                errors[key] = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "Cannot read $key", e)
            }
        }
        read("SDK.loaded") { Dilink5SdkInjector.ensure(app) || Dilink5SdkInjector.isLoadable(app) }
        read("Climate.powerOn") {
            val state = climate.snapshot()
            state.powerOn ?: throw IllegalArgumentException("Climate power unavailable: ${state.bindError}")
        }
        read("Headlights.selector") {
            val reading = headlights.readMode()
            values["Headlights.readPaths"] = reading.detail
            reading.mode ?: throw IllegalArgumentException(reading.detail)
        }
        for ((label, className) in DEVICES) {
            read("$label.binding") {
                val type = Class.forName(className, false, app.classLoader)
                val device = type.getMethod("getInstance", Context::class.java)
                    .invoke(null, BydPermissionContext(app))
                    ?: throw IllegalArgumentException("getInstance returned null")
                val constants = type.fields.filter {
                    Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
                        it.type == Int::class.javaPrimitiveType &&
                        CONSTANT_PATTERN.containsMatchIn(it.name)
                }
                constants.forEach { field -> read("$label.${field.name}") { field.getInt(null) } }
                val getters = when (label) {
                    "Bodywork" -> listOf("getWindowPermitState", "getWindowAntiPinchConfig")
                    "Instrument" -> listOf("getDayNightMode", "getInstrumentTheme")
                    else -> listOf("getINSTheme")
                }
                getters.forEach { name -> read("$label.$name") { type.getMethod(name).invoke(device) } }
                if (label == "Bodywork") {
                    constants.filter { it.name.startsWith("BODYWORK_CMD_WINDOW_") }.forEach { field ->
                        for (getter in listOf("getWindowState", "getWindowOpenPercent", "getWindowControlState")) {
                            read("$label.$getter(${field.name})") {
                                type.getMethod(getter, Int::class.javaPrimitiveType)
                                    .invoke(device, field.getInt(null))
                            }
                        }
                    }
                }
                "bound; raw getter values are not validated command mappings"
            }
        }
        for ((namespace, uri) in listOf(
            "system" to Settings.System.CONTENT_URI,
            "secure" to Settings.Secure.CONTENT_URI,
            "global" to Settings.Global.CONTENT_URI,
        )) {
            read("Android.$namespace.scan") {
                val cursor = app.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)
                    ?: throw IllegalArgumentException("Settings provider returned no cursor")
                cursor.use {
                    val nameIndex = it.getColumnIndexOrThrow("name")
                    val valueIndex = it.getColumnIndexOrThrow("value")
                    while (it.moveToNext()) {
                        val name = it.getString(nameIndex)
                        if (SETTING_PATTERN.containsMatchIn(name)) {
                            values["Android.$namespace.$name"] = it.getString(valueIndex) ?: "<null>"
                        }
                    }
                }
                "complete"
            }
        }
        values["Android.configuration.uiMode"] = app.resources.configuration.uiMode.toString()
        return CampTestSnapshot(values, errors)
    }

    private companion object {
        const val TAG = "CampTestReader"
        val CONSTANT_PATTERN = Regex("WINDOW|MODE_DAY|MODE_NIGHT|THEME")
        val SETTING_PATTERN = Regex("brightness|night|theme|dark|display.?mode", RegexOption.IGNORE_CASE)
        val DEVICES = listOf(
            "Bodywork" to "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            "Instrument" to "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            "Setting" to "android.hardware.bydauto.setting.BYDAutoSettingDevice",
        )
    }
}
