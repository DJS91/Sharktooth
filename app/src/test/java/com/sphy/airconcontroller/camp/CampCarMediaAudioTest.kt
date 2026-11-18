package com.sphy.airconcontroller.camp

import com.sphy.airconcontroller.adb.AdbPermissionManager.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CampCarMediaAudioTest {
    private fun dump(muted: Boolean = false, volume: Int = 3, navigationMuted: Boolean = false) = """
        Use dynamic routing? true
        Use car volume group muting? true
        Use hal audio focus? false
        Get suggested volume group by audio playback? false
        Get suggested volume group by call state? false
        Volume key event timeout ms? 0
        Master muted? false
        CarAudioZone(primary zone:0) isPrimary? true
        CarVolumeGroup id: 0
            Volume group gain values (min / max / default/ current): 0 39 10 $volume
            Current is muted? $muted
            Is muting restricted? false
            Context: 1 -> Address: bus0_media_out
            Context: 11 -> Address: bus0_media_out
            Context: 20 -> Address: bus0_media_out
        CarVolumeGroup id: 1
            Volume group gain values (min / max / default/ current): 0 10 4 1
            Current is muted? $navigationMuted
            Context: 2 -> Address: bus1_navigation_out
        CarVolumeGroup id: 5
            Volume group gain values (min / max / default/ current): 5 39 10 10
            Volume group can not be muted.
            Context: 10 -> Address: bus4_call_out
    """.trimIndent()

    private inner class Shell(var muted: Boolean = false) {
        var volume = 3
        var writes = 0
        var applies = true
        var navigationMuted = false
        var commandExit = 0
        var readExit = 0
        var firstVolumeKeyOnlyUnmutes = false
        val volumeCommands = mutableListOf<String>()
        var transform: (String) -> String = { it }
        val controller = CampCarMediaAudio { command ->
            when (command) {
                CampCarMediaAudio.READ_COMMAND -> ShellResult(readExit, transform(dump(muted, volume, navigationMuted)))
                CampCarMediaAudio.MUTE_COMMAND -> {
                    writes++
                    if (applies && commandExit == 0) muted = !muted
                    ShellResult(commandExit, if (commandExit == 0) "" else "input denied")
                }
                CampCarMediaAudio.VOLUME_UP_COMMAND, CampCarMediaAudio.VOLUME_DOWN_COMMAND -> {
                    volumeCommands += command
                    if (applies && commandExit == 0) {
                        if (!muted || !firstVolumeKeyOnlyUnmutes) {
                            volume += if (command == CampCarMediaAudio.VOLUME_UP_COMMAND) 1 else -1
                        }
                        muted = false
                    }
                    ShellResult(commandExit, if (commandExit == 0) "" else "input denied")
                }
                else -> error("Unexpected command: $command")
            }
        }
    }

    @Test fun mutesAndRestoresWithoutChangingVolumeAndNeverRetogglesAtTarget() = runBlocking {
        val shell = Shell()
        val saved = shell.controller.snapshot().media
        assertTrue(shell.controller.setMuted(true).success)
        assertTrue(shell.controller.setMuted(true).success)
        assertEquals(1, shell.writes)
        assertEquals(saved.volume, shell.volume)
        assertTrue(shell.controller.setMuted(saved.muted!!, saved.volume).success)
        assertFalse(shell.muted)
        assertEquals(2, shell.writes)
    }

    @Test fun originallyMutedMediaStaysMutedAcrossEntryAndRecoveredRestoration() = runBlocking {
        val shell = Shell(muted = true)
        val saved = shell.controller.snapshot().media
        assertTrue(shell.controller.setMuted(true).success)
        val recovered = CampCarMediaAudio { command ->
            assertEquals(CampCarMediaAudio.READ_COMMAND, command)
            ShellResult(0, dump(muted = true))
        }
        assertTrue(recovered.setMuted(saved.muted!!, saved.volume).success)
        assertEquals(0, shell.writes)
    }

    @Test fun acceptedButIgnoredKeyFailsWithoutSendingASecondToggle() = runBlocking {
        val shell = Shell().apply { applies = false }
        val result = shell.controller.setMuted(true)
        assertFalse(result.success)
        assertTrue(result.detail.contains("not confirmed"))
        assertEquals(1, shell.writes)
    }

    @Test fun failedReadOrUnsupportedRoutingNeverSendsKey() = runBlocking {
        val corruptions: List<(String) -> String> = listOf(
            { "Permission denied" },
            { it.replace("by audio playback? false", "by audio playback? true") },
            { it.replace("by call state? false", "by call state? true") },
            { it.replace("Current is muted? false", "Current is muted? unknown") },
            { it.replace("Context: 1 -> Address: bus0_media_out", "Context: 10 -> Address: bus4_call_out") },
            { it.replace("primary zone:0", "primary zone:1") },
            { it.replace("0 39 10 3", "0 39 10 99") },
            { it.replace("0 39 10 3", "0 39 10 999999999999999") },
        )
        for (transform in corruptions) {
            val shell = Shell().apply { this.transform = transform }
            try {
                shell.controller.setMuted(true)
                fail("Invalid read-back must fail explicitly")
            } catch (_: IllegalStateException) {
                assertEquals(0, shell.writes)
            }
        }
        val denied = Shell().apply { readExit = 1 }
        try {
            denied.controller.snapshot()
            fail("Shell read error must fail preflight")
        } catch (_: IllegalStateException) {
            assertEquals(0, denied.writes)
        }
    }

    @Test fun commandFailureAndUnexpectedOtherGroupChangesAreNotSuccess() = runBlocking {
        val denied = Shell().apply { commandExit = 1 }
        assertFalse(denied.controller.setMuted(true).success)
        assertFalse(denied.muted)
        val changed = Shell().apply {
            transform = { text ->
                if (writes > 0) text.replace("Master muted? false", "Master muted? true") else text
            }
        }
        assertFalse(changed.controller.setMuted(true).success)
        assertEquals(1, changed.writes)
        val navigation = Shell().apply {
            transform = { text ->
                if (writes > 0) text.replace("Current is muted? false", "Current is muted? true") else text
            }
        }
        assertFalse(navigation.controller.setMuted(true).success)
    }

    @Test fun restoresSavedVolumeFromEitherDirectionThenRestoresActualOriginalMuteState() = runBlocking {
        for (current in listOf(1, 5)) {
            for (originalMute in listOf(false, true)) {
                for (onlyUnmutes in listOf(false, true)) {
                    val shell = Shell(muted = true).apply {
                        volume = current
                        firstVolumeKeyOnlyUnmutes = onlyUnmutes
                    }
                    assertTrue(shell.controller.setMuted(originalMute, savedVolume = 3).success)
                    assertEquals(3, shell.volume)
                    assertEquals(originalMute, shell.muted)
                    assertEquals(if (onlyUnmutes) 3 else 2, shell.volumeCommands.size)
                    assertTrue(shell.volumeCommands.all {
                        it == if (current < 3) CampCarMediaAudio.VOLUME_UP_COMMAND else CampCarMediaAudio.VOLUME_DOWN_COMMAND
                    })
                }
            }
        }
    }

    @Test fun failedOrIgnoredVolumeWritesStopInsteadOfLoopingOrClaimingRestoration() = runBlocking {
        for (exit in listOf(0, 1)) {
            val shell = Shell(muted = true).apply { volume = 4; applies = false; commandExit = exit }
            assertFalse(shell.controller.setMuted(false, savedVolume = 3).success)
            assertEquals(4, shell.volume)
            assertTrue(shell.muted)
            assertEquals(1, shell.volumeCommands.size)
            assertEquals(0, shell.writes)
        }
    }

    @Test fun restoresAcrossFullVolumeRangeIncludingAnInitialUnmuteOnlyKey() = runBlocking {
        val shell = Shell(muted = true).apply {
            volume = 0
            firstVolumeKeyOnlyUnmutes = true
        }
        assertTrue(shell.controller.setMuted(true, savedVolume = 39).success)
        assertEquals(39, shell.volume)
        assertTrue(shell.muted)
        assertEquals(40, shell.volumeCommands.size)
    }

    @Test fun wrongDirectionOrNonMediaChangeStopsVolumeRestorationImmediately() = runBlocking {
        for (changesMaster in listOf(false, true)) {
            val shell = Shell().apply {
                volume = 4
                transform = { text ->
                    if (volumeCommands.isEmpty()) text
                    else if (changesMaster) text.replace("Master muted? false", "Master muted? true")
                    else text.replace("0 39 10 3", "0 39 10 5")
                }
            }
            assertFalse(shell.controller.setMuted(false, savedVolume = 3).success)
            assertEquals(1, shell.volumeCommands.size)
            assertEquals(0, shell.writes)
        }
    }
}
