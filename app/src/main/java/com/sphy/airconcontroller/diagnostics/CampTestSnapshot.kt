package com.sphy.airconcontroller.diagnostics

data class CampTestSnapshot(
    val values: Map<String, String>,
    val errors: Map<String, String>,
) {
    fun report(): String = buildString {
        values.toSortedMap().forEach { (key, value) -> appendLine("$key = $value") }
        errors.toSortedMap().forEach { (key, error) -> appendLine("$key: UNAVAILABLE ($error)") }
    }.trimEnd()

    fun changesFrom(before: CampTestSnapshot): String = buildString {
        val keys = (before.values.keys + values.keys + before.errors.keys + errors.keys).sorted()
        var changes = 0
        keys.forEach { key ->
            val old = before.values[key]
            val new = values[key]
            if (old == null || new == null) {
                appendLine("$key: comparison unavailable")
            } else if (old != new) {
                appendLine("$key: $old -> $new")
                changes++
            }
        }
        if (changes == 0) appendLine("No changes observed among readable values; this does not prove a control is unsupported.")
    }.trimEnd()
}
