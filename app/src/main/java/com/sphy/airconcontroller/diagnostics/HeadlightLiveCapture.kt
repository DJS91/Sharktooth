package com.sphy.airconcontroller.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun headlightSelectorLabel(snapshot: CampTestSnapshot): String = when (snapshot.values["Headlights.selector"]) {
    "1" -> "OFF (1)"
    "2" -> "AUTO (2)"
    "3" -> "parking lights (3)"
    "4" -> "low beam (4)"
    else -> "unavailable (not OFF)"
}

/** Bounded, read-only observations, never a list of validated commands to replay. */
internal class HeadlightLiveCapture(private val startedAtMillis: Long, private val ownPid: Int) {
    private var previous: Map<String, String>? = null
    private val seenLogs = LinkedHashSet<String>()
    private val events = ArrayDeque<String>()
    private var eventCount = 0
    private var selector = "unavailable (not OFF)"
    private var rearLamp = "unavailable"
    private var readable = 0
    private var lastSample = "waiting..."
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    var logStatus = "connecting..."
    var stopped = false

    fun sample(snapshot: CampTestSnapshot, time: String) {
        lastSample = time
        selector = headlightSelectorLabel(snapshot)
        rearLamp = snapshot.values["Bodywork.getRearLampLightState"] ?: "unavailable"
        val values = snapshot.values.filterKeys { it == "Headlights.selector" || GETTER.matches(it) }
        readable = values.size
        val current = values + snapshot.errors.filterKeys {
            it == "Headlights.selector" || GETTER.matches(it)
        }.mapValues { "unavailable" }
        val before = previous
        if (before != null) {
            (before.keys + current.keys).sorted().forEach { key ->
                val old = before[key] ?: "unavailable"
                val new = current[key] ?: "unavailable"
                if (old != new) add("$time SDK raw $key: $old -> $new")
            }
        }
        previous = current
    }

    fun logs(output: String) {
        output.lineSequence().forEach { line ->
            val match = LOG_LINE.find(line) ?: return@forEach
            val epoch = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            if (epoch * 1000 < startedAtMillis || match.groupValues[2].toIntOrNull() == ownPid ||
                !LIGHTING.containsMatchIn(line) || !seenLogs.add(line)) return@forEach
            while (seenLogs.size > 200) seenLogs.remove(seenLogs.first())
            // Keep the OEM tag, PID and event payload, but not the lengthy epoch timestamp.
            add("${clock.format(Date((epoch * 1000).toLong()))} LOG ${line.substringAfter(match.groupValues[1]).trim()}")
        }

    }

    fun report(): String = buildString {
        appendLine(if (stopped) "STOPPED - headlight stalk capture" else "LIVE - turn the stalk while safely parked")
        appendLine("Pause 2 seconds in OFF, AUTO and low beam.")
        appendLine("Selector: $selector")
        appendLine("Rear-lamp feedback (raw): $rearLamp")
        appendLine("Last sample: $lastSample; readable lighting values: $readable")
        appendLine("Car logs: $logStatus")
        appendLine("Readings/logs are observations, NOT verified commands. No writes.")
        appendLine("Latest ${events.size} of $eventCount events:")
        if (events.isEmpty()) append("No changes seen yet. The car may not expose stalk events.")
        else append(events.joinToString("\n"))
    }.trimEnd()

    private fun add(text: String) {
        events.addLast(if (text.length > 180) text.take(177) + "..." else text)
        eventCount++
        while (events.size > 8) events.removeFirst()
    }

    private companion object {
        val GETTER = Regex("(Bodywork|Light|Sensor|Instrument)\\.(get|is|has).*")
        val LOG_LINE = Regex("^\\s*(\\d+\\.\\d+)\\s+(\\d+)\\s+\\d+\\s+[VDIWEF]\\s+")
        val LIGHTING = Regex(
            "head.?light|head.?lamp|low.?beam|high.?beam|dipped.?beam|light.?switch|ambient.?light|sunlight|\\blux\\b|1011875880|1276153912",
            RegexOption.IGNORE_CASE,
        )
    }
}
