package com.sphy.airconcontroller.camp

import com.sphy.airconcontroller.adb.AdbPermissionManager.ShellResult
import kotlinx.coroutines.delay

internal class CampCarMediaAudio(private val shell: suspend (String) -> ShellResult) {
    data class Group(val volume: Int, val muted: Boolean?)
    data class State(val masterMuted: Boolean, val groups: Map<Int, Group>) {
        val media: Group get() = groups.getValue(0)
    }

    suspend fun snapshot(): State {
        val result = shell(READ_COMMAND)
        check(result.exitCode == 0) { "Cannot read car media mute: ${result.output}" }
        return parse(result.output)
    }

    suspend fun setMuted(muted: Boolean, savedVolume: Int? = null): CampResult {
        val before = snapshot()
        if (before.media.muted == muted) return confirmed(before, muted, savedVolume)
        // This is a toggle: never resend it on timeout, or it could undo a delayed mute.
        val command = shell(MUTE_COMMAND)
        if (command.exitCode != 0) return CampResult(false, "Car media mute key failed: ${command.output}")
        var after = before
        repeat(10) {
            delay(150)
            after = snapshot()
            if (after.masterMuted != before.masterMuted ||
                after.groups.filterKeys { it != 0 } != before.groups.filterKeys { it != 0 }) {
                return CampResult(false, "Unexpected non-media audio change after mute key; check car audio settings.")
            }
            if (after.media.volume != before.media.volume) {
                return CampResult(false, "Media volume changed during mute confirmation; check car audio settings.")
            }
            if (after.media.muted == muted) return confirmed(after, muted, savedVolume)
        }
        return CampResult(false, "Car media mute not confirmed: expected=$muted actual=${after.media.muted}; no second toggle sent.")
    }

    private fun confirmed(state: State, muted: Boolean, savedVolume: Int?): CampResult {
        val volume = state.media.volume
        if (savedVolume != null && volume != savedVolume) {
            return CampResult(false, "Media mute=$muted restored, but volume=$volume differs from saved $savedVolume. " +
                "Restore media volume to $savedVolume with the car controls, then retry restoration.")
        }
        return CampResult(true, "Car media group 0 mute=$muted confirmed; volume=$volume preserved.")
    }

    companion object {
        const val READ_COMMAND = "dumpsys car_service --services CarEnhancedAudioService"
        const val MUTE_COMMAND = "input keyevent 164"

        internal fun parse(text: String): State {
            fun configured(label: String, value: String) =
                Regex("(?m)^\\s*${Regex.escape(label)} ${Regex.escape(value)}\\s*$").containsMatchIn(text)
            fun number(value: String): Int = checkNotNull(value.toIntOrNull()) {
                "Invalid car audio numeric reading: $value"
            }
            // Only the verified routing policy may use a context-sensitive volume key.
            check(configured("Use dynamic routing?", "true") &&
                configured("Use car volume group muting?", "true") &&
                configured("Use hal audio focus?", "false") &&
                configured("Get suggested volume group by audio playback?", "false") &&
                configured("Get suggested volume group by call state?", "false") &&
                configured("Volume key event timeout ms?", "0")) {
                "Unsupported car audio mute-key routing."
            }
            val zones = Regex("CarAudioZone\\(([^)]*)\\)").findAll(text).toList()
            check(zones.size == 1 && zones.single().groupValues[1] == "primary zone:0") {
                "Unsupported car audio zones."
            }
            val master = checkNotNull(Regex("Master muted\\? (true|false)").find(text)) {
                "Car master mute state unavailable."
            }.groupValues[1].toBooleanStrict()
            val headings = Regex("(?m)^\\s*CarVolumeGroup id: (\\d+)\\s*$").findAll(text).toList()
            check(headings.isNotEmpty()) { "Car audio groups unavailable." }
            val groups = linkedMapOf<Int, Group>()
            for ((index, heading) in headings.withIndex()) {
                val id = number(heading.groupValues[1])
                val body = text.substring(heading.range.last + 1,
                    headings.getOrNull(index + 1)?.range?.first ?: text.length)
                val gain = checkNotNull(Regex(
                    "Volume group gain values \\(min / max / default/ current\\): (\\d+) (\\d+) (\\d+) (\\d+)"
                ).find(body)) { "Car audio group $id volume unavailable." }.groupValues
                val volume = number(gain[4])
                check(volume in number(gain[1])..number(gain[2])) {
                    "Invalid car audio group $id volume."
                }
                val mute = Regex("Current is muted\\? (true|false)").find(body)?.groupValues?.get(1)
                    ?.toBooleanStrict()
                check(mute != null || body.contains("Volume group can not be muted.")) {
                    "Car audio group $id mute state unavailable."
                }
                check(groups.put(id, Group(volume, mute)) == null) { "Duplicate car audio group $id." }
                if (id == 0) {
                    val contexts = Regex("Context: (\\d+) -> Address: (\\S+)").findAll(body).toList()
                    check(mute != null && contexts.any { it.groupValues[1] == "1" } &&
                        contexts.all { number(it.groupValues[1]) in setOf(1, 11, 12, 20, 23) &&
                            it.groupValues[2] == "bus0_media_out" } &&
                        body.contains("Is muting restricted? false")) {
                        "Car group 0 is not an unrestricted media-only group."
                    }
                }
            }
            check(0 in groups && 5 in groups && groups.getValue(5).muted == null) {
                "Verified media/safety audio group layout unavailable."
            }
            return State(master, groups)
        }
    }
}
