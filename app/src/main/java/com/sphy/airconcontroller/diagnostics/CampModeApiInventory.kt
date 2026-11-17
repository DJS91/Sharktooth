package com.sphy.airconcontroller.diagnostics

import java.lang.reflect.Modifier

private val CAMP_API_PATTERN = Regex("window|theme|night|day|display.?mode", RegexOption.IGNORE_CASE)

internal fun campModeApiInventory(classLoader: ClassLoader, classes: List<Pair<String, String>>): String =
    buildString {
        appendLine("Read-only SDK metadata; no vehicle methods or settings are invoked.")
        for ((label, className) in classes) {
            appendLine()
            appendLine("$label ($className):")
            try {
                val type = Class.forName(className, false, classLoader)
                val methods = type.methods
                    .filter { CAMP_API_PATTERN.containsMatchIn(it.name) }
                    .sortedWith(compareBy({ it.name }, { it.parameterTypes.joinToString { param -> param.name } }))
                if (methods.isEmpty()) appendLine("  No matching public methods found.")
                methods.forEach { method ->
                    val params = method.parameterTypes.joinToString(", ") { it.name }
                    appendLine("  ${method.returnType.name} ${method.name}($params)")
                }
                (listOf(type) + type.declaredClasses).forEach { owner ->
                    owner.fields.filter { field ->
                        Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers) &&
                            field.type == Int::class.javaPrimitiveType &&
                            CAMP_API_PATTERN.containsMatchIn(field.name)
                    }.sortedBy { it.name }.forEach { field ->
                        appendLine("  constant ${owner.name}.${field.name}")
                    }
                }
            } catch (e: ClassNotFoundException) {
                appendLine("  Class unavailable: ${e.message}")
            } catch (e: LinkageError) {
                appendLine("  SDK metadata unavailable: ${e.message}")
            } catch (e: SecurityException) {
                appendLine("  SDK metadata inaccessible: ${e.message}")
            }
        }
    }.trimEnd()
